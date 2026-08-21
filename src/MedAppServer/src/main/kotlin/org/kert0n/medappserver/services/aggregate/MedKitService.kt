package org.kert0n.medappserver.services.aggregate

import com.sksamuel.aedile.core.Cache
import kotlin.uuid.Uuid
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Invitation
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
    private val medKitTokenCache: Cache<String, Invitation>
) {

    private val logger = LoggerFactory.getLogger(MedKitService::class.java)

    @Transactional(propagation = MANDATORY)
    fun create(userId: Uuid): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit(members = setOf(userId))
        medKits.insert(medKit)
        return medKit
    }

    /**
     * Аптечка вызывающего. Недоступная и несуществующая неотличимы намеренно.
     *
     * Единственный способ получить `MedKit`: чтение и есть проверка доступа.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun get(medKitId: Uuid, userId: Uuid): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        return medKits.find(medKitId, userId) ?: throw NotAMember()
    }

    /** Все аптечки участника — целиком и одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun allOfUser(userId: Uuid): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    /** По идентификатору — то же самое плюс своё чтение, в котором и проверяется доступ. */
    @Transactional(propagation = MANDATORY)
    fun invite(medKitId: Uuid, userId: Uuid): String = invite(get(medKitId, userId), userId)

    @Transactional(propagation = MANDATORY)
    fun invite(medKit: MedKit, invitedBy: Uuid): String {
        logger.debug("Sharing medkit {} by user: {}", medKit.id, invitedBy)
        // Аптечка приходит прочитанной — читал её тот, кто приглашает, и это и есть его право.
        val invitation = Invitation(medKit, invitedBy)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = invitation
        return key
    }

    /**
     * Вступление по приглашению — единственный способ попасть в аптечку.
     *
     * Вступающего в ней ещё нет, поэтому аптечка читается правами **пригласившего**: он в ней
     * состоит, и обычное скоупленное чтение работает. Нескоупленных чтений в приложении не
     * появляется — см. [Invitation] о том, что из этого следует.
     *
     * Правило «дважды не вступают» решает сам агрегат: состав у него на руках.
     */
    @Transactional(propagation = MANDATORY)
    fun joinByInvitation(key: String, userId: Uuid): MedKit {
        logger.debug("Adding user {} to medkit by invitation", userId)
        val invitation = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()

        val joined = get(invitation.medKitId, invitation.invitedBy).join(userId)
        medKits.save(joined)
        return joined
    }

    /**
     * `null` — вышел последний, и аптечка удалена вместе с содержимым.
     *
     * Брони выходящего лежат в чужом агрегате: их убирает оркестратор.
     */
    @Transactional(propagation = MANDATORY)
    fun leave(medKitId: Uuid, userId: Uuid): MedKit? = leave(get(medKitId, userId), userId)

    @Transactional(propagation = MANDATORY)
    fun leave(medKit: MedKit, userId: Uuid): MedKit? {
        logger.debug("Removing user {} from medkit {}", userId, medKit.id)
        val left = medKit.leave(userId)

        if (left == null) {
            medKits.delete(medKit)
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
    fun delete(medKitId: Uuid, userId: Uuid) = delete(get(medKitId, userId))

    @Transactional(propagation = MANDATORY)
    fun delete(medKit: MedKit) = medKits.delete(medKit)
}
