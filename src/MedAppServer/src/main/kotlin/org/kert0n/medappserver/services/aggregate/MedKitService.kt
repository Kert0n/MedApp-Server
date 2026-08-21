package org.kert0n.medappserver.services.aggregate

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
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

    @Transactional(propagation = MANDATORY)
    fun create(userId: UUID): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit(members = setOf(userId))
        medKits.insert(medKit)
        return medKit
    }

    /** Аптечка, доступная вызывающему, или `null`. Доступ проверяет сам запрос. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun findAccessible(medKitId: UUID, userId: UUID): MedKit? = medKits.findAccessible(medKitId, userId)

    /** Аптечка, доступная вызывающему, или 404 — недоступная и несуществующая неотличимы. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun requireAccessible(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        return findAccessible(medKitId, userId) ?: throw NotAMember()
    }

    /** Все аптечки участника — целиком и одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun allOfUser(userId: UUID): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    @Transactional(propagation = MANDATORY)
    fun invite(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        // Право проверяет чтение состава. Версией это не проверяется: чужое вступление в ту же
        // аптечку к правам приглашающего отношения не имеет.
        requireAccessible(medKitId, userId)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = medKitId
        return key
    }

    /**
     * Вступление — единственное место, где вызывающего в аптечке ещё нет.
     *
     * Поэтому оно не читает её: чтение чужой аптечки было бы дырой в правиле «доступ проверяет
     * запрос». Сначала пишется членство, и только потом аптечка читается уже как своя.
     * Правила в домене нет и быть не может: `MedKit` — это состав, а прочитать состав аптечки,
     * к которой доступа ещё нет, нельзя. Поэтому «дважды не вступают» проверяется по
     * собственной строке членства, а несуществующая аптечка приезжает нарушением ключа.
     */
    @Transactional(propagation = MANDATORY)
    fun join(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Adding user {} to medkit {}", userId, medKitId)
        // Правило читается здесь; собственную строку членства для этого читать можно — это не
        // чужая аптечка. Ключ ниже страхует гонку двух одновременных вступлений.
        if (medKits.isMember(medKitId, userId)) throw AlreadyMember()
        medKits.addMember(medKitId, userId)
        return requireAccessible(medKitId, userId)
    }

    @Transactional(propagation = MANDATORY)
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
    @Transactional(propagation = MANDATORY)
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
     * Удаляется уже прочитанная аптечка.
     *
     * Идентификатора здесь нет намеренно: получить `MedKit` можно только скоупленным чтением,
     * поэтому команде нечего перепроверять — а перепроверка стоила бы второго запроса.
     */
    @Transactional(propagation = MANDATORY)
    fun delete(medKit: MedKit) = medKits.delete(medKit.id)
}
