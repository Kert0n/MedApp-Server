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
 * Аптечка: жизненный цикл отдельных membership.
 *
 * Здесь транзакция, проверка доступа и ключ приглашения.
 * Ключ не доменное понятие, а секрет с временем жизни, поэтому живёт рядом с тем, кто его
 * выдаёт.
 */
@Service
class MedKitService(
    private val medKits: MedKitStore,
    private val access: MedKitAccessService,
    private val securityService: SecurityService,
    private val medKitTokenCache: Cache<String, Invitation>
) {

    private val logger = LoggerFactory.getLogger(MedKitService::class.java)

    @Transactional(propagation = MANDATORY)
    fun create(userId: Uuid): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit()
        medKits.insert(medKit, userId)
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

    /** Все аптечки участника со счётчиками — одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun allOfUser(userId: Uuid): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    /**
     * Приглашение в аптечку: удержание доступа и есть проверка права пригласить.
     *
     * Аптечка после этого не читается. Приглашению нужен только идентификатор, а `get` посчитал
     * бы `COUNT` участников под блокировкой ради значения, которое тут же выбрасывается (#134).
     */
    @Transactional(propagation = MANDATORY)
    fun invite(medKitId: Uuid, userId: Uuid): String {
        access.holdContentAccess(setOf(medKitId), userId)
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        val invitation = Invitation(medKitId, userId)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = invitation
        return key
    }

    /**
     * Вступление по приглашению — единственный способ попасть в аптечку.
     *
     * Вступающего в аптечке ещё нет, поэтому доступ удерживается правами **пригласившего**: он в
     * ней состоит, и блокировка это заново подтверждает. Нескоупленных чтений в приложении не
     * появляется — см. [Invitation] о том, что из этого следует.
     *
     * Возвращается идентификатор, а не проекция: `userCount` под блокировкой не считается (#134),
     * а вызывающему всё равно нужна картина уже с самим вступившим.
     *
     * Правило «дважды не вступают» выражено отдельной строкой, а страхует его составной ключ.
     */
    @Transactional(propagation = MANDATORY)
    fun joinByInvitation(key: String, userId: Uuid): Uuid {
        logger.debug("Adding user {} to medkit by invitation", userId)
        val invitation = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()

        access.holdLifecycleAccess(setOf(invitation.medKitId), invitation.invitedBy)
        medKits.insertMembership(invitation.medKitId, userId)
        return invitation.medKitId
    }

    /**
     * `null` — вышел последний, и аптечка удалена вместе с содержимым.
     *
     * Брони выходящего лежат в чужом агрегате: их убирает оркестратор.
     */
    @Transactional(propagation = MANDATORY)
    internal fun removeMembership(medKitId: Uuid, userId: Uuid): Boolean {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        medKits.deleteMembership(medKitId, userId)
        if (!medKits.hasMembers(medKitId)) {
            medKits.delete(medKitId)
            return true
        }
        return false
    }

    @Transactional(propagation = MANDATORY)
    internal fun deleteRoot(medKitId: Uuid) {
        medKits.delete(medKitId)
    }
}
