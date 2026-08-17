package org.kert0n.medappserver.services.models

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.MedKitOverview
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Аптечка: жизненный цикл участников.
 *
 * Правила членства живут в `domain.medkit.MedKit`; здесь — транзакция, проверка доступа и
 * ключ приглашения. Ключ не доменное понятие: это одноразовый секрет с временем жизни, и
 * место ему рядом с тем, кто умеет его выдавать и хранить.
 */
@Service
class MedKitService(
    private val medKits: MedKitStore,
    private val securityService: SecurityService,
    private val medKitTokenCache: Cache<String, UUID>
) {

    private val logger = LoggerFactory.getLogger(MedKitService::class.java)

    @Transactional
    fun createNew(userId: UUID): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit(members = setOf(userId))
        medKits.insert(medKit)
        return medKit
    }

    @Transactional(readOnly = true)
    fun findById(medKitId: UUID): MedKit = medKits.findById(medKitId) ?: throw NotAMember()

    /** Аптечка, доступная вызывающему, или 404 — недоступная и несуществующая неотличимы. */
    @Transactional(readOnly = true)
    fun requireAccessible(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        val medKit = medKits.findById(medKitId) ?: throw NotAMember()
        medKit.requireMember(userId)
        return medKit
    }

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    @Transactional(readOnly = true)
    fun overviews(userId: UUID): List<MedKitOverview> {
        logger.debug("Finding medkit overviews for user: {}", userId)
        return medKits.overviewsOf(userId)
    }

    @Transactional(readOnly = true)
    fun generateMedKitShareKey(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        requireAccessible(medKitId, userId)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = medKitId
        return key
    }

    @Transactional
    fun addUserToMedKit(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Adding user {} to medkit {}", userId, medKitId)
        val joined = findById(medKitId).join(userId)
        medKits.save(joined)
        return joined
    }

    @Transactional
    fun joinMedKitByKey(key: String, userId: UUID): MedKit {
        val medKitId = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()
        return addUserToMedKit(medKitId, userId)
    }

    /**
     * Выход участника.
     *
     * Возвращает `null`, когда вышел последний: аптечка удалена вместе с содержимым. Планы
     * выходящего в препаратах этой аптечки — забота оркестратора, они лежат в чужом агрегате.
     */
    @Transactional
    fun removeUserFromMedKit(medKitId: UUID, userId: UUID): MedKit? {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = requireAccessible(medKitId, userId).leave(userId)

        if (left == null) {
            medKits.delete(medKitId)
            return null
        }
        medKits.save(left)
        return left
    }

    @Transactional
    fun delete(medKitId: UUID) {
        medKits.delete(medKitId)
    }
}
