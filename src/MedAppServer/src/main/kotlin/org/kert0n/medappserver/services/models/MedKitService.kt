package org.kert0n.medappserver.services.models

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
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
    fun create(userId: UUID): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit(members = setOf(userId))
        medKits.insert(medKit)
        return medKit
    }

    /** Аптечка или `null`, если её нет. */
    @Transactional(readOnly = true)
    fun findById(medKitId: UUID): MedKit? = medKits.findById(medKitId)

    /** Аптечка или 404. */
    @Transactional(readOnly = true)
    fun requireById(medKitId: UUID): MedKit = findById(medKitId) ?: throw NotAMember()

    /** Аптечка, доступная вызывающему, или 404 — недоступная и несуществующая неотличимы. */
    @Transactional(readOnly = true)
    fun requireAccessible(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        val medKit = requireById(medKitId)
        medKit.requireMember(userId)
        return medKit
    }

    /**
     * Все аптечки участника — целиком.
     *
     * Раньше здесь было два чтения под двух вызывающих: идентификаторы для снимка и счётчики
     * для списка. Оба ушли: экономия не в том, чтобы вернуть поменьше полей, а в том, чтобы не
     * ходить в базу лишний раз, — а состав приходит тем же одним запросом, из которого счётчик
     * участников получается сам.
     */
    @Transactional(readOnly = true)
    fun allOfUser(userId: UUID): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    @Transactional(readOnly = true)
    fun invite(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        requireAccessible(medKitId, userId)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = medKitId
        return key
    }

    @Transactional
    fun join(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Adding user {} to medkit {}", userId, medKitId)
        val joined = requireById(medKitId).join(userId)
        medKits.save(joined)
        return joined
    }

    @Transactional
    fun joinByInvitation(key: String, userId: UUID): MedKit {
        val medKitId = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()
        return join(medKitId, userId)
    }

    /**
     * Выход участника.
     *
     * Возвращает `null`, когда вышел последний: аптечка удалена вместе с содержимым. Планы
     * выходящего в препаратах этой аптечки — забота оркестратора, они лежат в чужом агрегате.
     */
    @Transactional
    fun leave(medKitId: UUID, userId: UUID): MedKit? {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = requireAccessible(medKitId, userId).leave(userId)

        if (left == null) {
            medKits.delete(medKitId)
            return null
        }
        medKits.save(left)
        return left
    }

    /**
     * Удаление аптечки.
     *
     * Доступ проверяется здесь, а не только у вызывающего: команда, удаляющая аптечку по
     * одному идентификатору, рано или поздно будет вызвана и без проверки.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID) {
        requireAccessible(medKitId, userId)
        medKits.delete(medKitId)
    }
}
