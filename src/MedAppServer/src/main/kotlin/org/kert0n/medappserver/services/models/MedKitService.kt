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
 * Правила членства — в `domain.MedKit`; здесь транзакция, проверка доступа и ключ приглашения.
 * Ключ не доменное понятие, а секрет с временем жизни, поэтому живёт рядом с тем, кто его
 * выдаёт.
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

    /**
     * Аптечка в том составе, по которому решал клиент.
     *
     * Порядок проверок значим: сначала доступ, потом версия — иначе по коду ответа узнавалось бы
     * существование чужой аптечки.
     */
    @Transactional(readOnly = true)
    fun requireAccessibleAt(medKitId: UUID, userId: UUID, expectedVersion: Long): MedKit =
        requireAccessible(medKitId, userId).also { it.requireVersion(expectedVersion) }

    /** Аптечка, доступная вызывающему, или 404 — недоступная и несуществующая неотличимы. */
    @Transactional(readOnly = true)
    fun requireAccessible(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        val medKit = requireById(medKitId)
        medKit.requireMember(userId)
        return medKit
    }

    /** Все аптечки участника — целиком и одним запросом. */
    @Transactional(readOnly = true)
    fun allOfUser(userId: UUID): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    /** Состав удерживается до коммита: приглашать в аптечку, из которой уже вышел, нельзя. */
    @Transactional
    fun invite(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        medKits.requireUnchanged(requireAccessible(medKitId, userId))
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
     * `null` — вышел последний, и аптечка удалена вместе с содержимым.
     *
     * Брони выходящего лежат в чужом агрегате: их убирает оркестратор.
     */
    @Transactional
    fun leave(medKitId: UUID, userId: UUID, expectedVersion: Long): MedKit? {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = requireAccessibleAt(medKitId, userId, expectedVersion).leave(userId)

        if (left == null) {
            medKits.delete(medKitId)
            return null
        }
        medKits.save(left)
        return left
    }

    /**
     * Требует, чтобы состав дожил до коммита таким, каким его прочитали.
     *
     * Для тех, кто решает **по составу, а меняет другое**: переезд упаковки смотрит, кто её
     * увидит после переезда, и убирает брони остальных.
     */
    @Transactional
    fun requireUnchanged(medKit: MedKit) = medKits.requireUnchanged(medKit)

    /** Доступ проверяется здесь: команда по одному идентификатору однажды придёт без проверки. */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, expectedVersion: Long) {
        requireAccessibleAt(medKitId, userId, expectedVersion)
        medKits.delete(medKitId)
    }
}
