package org.kert0n.medappserver.services.orchestrator

import com.sksamuel.aedile.core.Cache
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Invitation
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.aggregate.MedKitAccessService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Вступление по приглашению — единственный способ попасть в аптечку.
 *
 * Вступающего в аптечке ещё нет, поэтому доступ удерживается правами **пригласившего**. Режим
 * исключительный: команда меняет состав участников, и параллельный выход этого же пригласившего
 * обязан либо дождаться её, либо отменить приглашение целиком — см. [Invitation].
 *
 * Возвращается идентификатор, а не проекция: `userCount` под блокировкой не считается (#134), а
 * вызывающему всё равно нужна картина уже с самим вступившим.
 *
 * Правило «дважды не вступают» выражено отдельной строкой, а страхует его составной ключ.
 */
@Service
class MedKitJoining(
    private val access: MedKitAccessService,
    private val medKitService: MedKitService,
    private val securityService: SecurityService,
    private val medKitTokenCache: Cache<String, Invitation>
) {

    private val logger = LoggerFactory.getLogger(MedKitJoining::class.java)

    @Transactional(propagation = MANDATORY)
    fun joinByInvitation(key: String, userId: Uuid): Uuid {
        logger.debug("Adding user {} to medkit by invitation", userId)
        val invitation = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()

        access.holdLifecycleAccess(setOf(invitation.medKitId), invitation.invitedBy)
        medKitService.addMembership(invitation.medKitId, userId)
        return invitation.medKitId
    }
}
