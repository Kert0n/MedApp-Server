package org.kert0n.medappserver.application.orchestrator

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.application.model.InvitationResult
import org.kert0n.medappserver.application.model.MedKitCreatedResult
import org.kert0n.medappserver.application.service.MedKitService
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.domain.error.InvitationNotFound
import org.kert0n.medappserver.domain.error.InvalidMedKitTarget
import org.kert0n.medappserver.services.security.SecurityService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class MedKitOrchestrator(
    private val medKitService: MedKitService,
    private val drugService: DrugService,
    private val securityService: SecurityService,
    @Qualifier("medKitTokenCache") private val invitations: Cache<String, UUID>,
    transactionManager: PlatformTransactionManager
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Transactional
    fun create(userId: UUID): MedKitCreatedResult = medKitService.create(userId)

    @Transactional
    fun createInvitation(userId: UUID, medKitId: UUID): InvitationResult {
        medKitService.lockAccessible(userId, listOf(medKitId))
        val key = securityService.generateKey(16)
        invitations[securityService.hashToken(key)] = medKitId
        return InvitationResult(key)
    }

    fun join(userId: UUID, key: String): MedKitCreatedResult {
        val medKitId = invitations.getOrNull(securityService.hashToken(key))
            ?: throw InvitationNotFound()
        return requireNotNull(transaction.execute { medKitService.join(userId, medKitId) })
    }

    @Transactional
    fun leave(userId: UUID, medKitId: UUID) {
        val decision = medKitService.prepareLeave(userId, medKitId)
        if (decision.deleteMedKit) drugService.lockAllByMedKitIds(listOf(medKitId))
        medKitService.completeLeave(userId, medKitId, decision)
    }

    @Transactional
    fun delete(userId: UUID, medKitId: UUID, targetMedKitId: UUID? = null) {
        if (targetMedKitId == medKitId) throw InvalidMedKitTarget()
        val lockIds = listOfNotNull(medKitId, targetMedKitId).toSet()
        medKitService.lockAccessible(userId, lockIds)
        drugService.lockAllByMedKitIds(lockIds)
        if (targetMedKitId != null) drugService.moveAll(medKitId, targetMedKitId)
        medKitService.deleteLocked(medKitId)
    }
}
