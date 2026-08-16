package org.kert0n.medappserver.application.service

import org.kert0n.medappserver.application.model.MedKitCreatedResult
import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.domain.error.UserNotFound
import org.kert0n.medappserver.domain.medkit.LeaveMedKitDecision
import org.kert0n.medappserver.domain.medkit.MedKit
import org.kert0n.medappserver.persistence.repository.MedKitAggregateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service("aggregateMedKitService")
class MedKitService(
    private val repository: MedKitAggregateRepository
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun create(userId: UUID): MedKitCreatedResult {
        if (!repository.userExists(userId)) throw UserNotFound(userId)
        val medKit = MedKit.create(userId)
        repository.insert(medKit, userId)
        return MedKitCreatedResult(medKit.id)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAccessible(userId: UUID, medKitIds: Collection<UUID>) {
        requireLockedAccess(userId, medKitIds)
    }

    private fun requireLockedAccess(userId: UUID, medKitIds: Collection<UUID>) {
        val expected = medKitIds.toSet()
        val locked = repository.lockAccessible(userId, expected)
        if (locked.size != expected.size) throw MedKitNotFound(expected.first { it !in locked })
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun join(userId: UUID, medKitId: UUID): MedKitCreatedResult {
        if (!repository.userExists(userId)) throw UserNotFound(userId)
        repository.lock(medKitId) ?: throw MedKitNotFound(medKitId)
        val medKit = repository.load(medKitId) ?: throw MedKitNotFound(medKitId)
        if (medKit.join(userId)) repository.insertMembership(medKitId, userId)
        return MedKitCreatedResult(medKitId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareLeave(userId: UUID, medKitId: UUID): LeaveMedKitDecision {
        requireLockedAccess(userId, listOf(medKitId))
        val medKit = repository.load(medKitId) ?: throw MedKitNotFound(medKitId)
        return medKit.leave(userId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun completeLeave(userId: UUID, medKitId: UUID, decision: LeaveMedKitDecision) {
        if (decision.deleteMedKit) repository.delete(medKitId) else {
            repository.deletePlansForMember(medKitId, userId)
            repository.deleteMembership(medKitId, userId)
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteLocked(medKitId: UUID) {
        repository.delete(medKitId)
    }
}
