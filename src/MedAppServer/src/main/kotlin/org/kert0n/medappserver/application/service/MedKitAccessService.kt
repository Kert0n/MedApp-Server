package org.kert0n.medappserver.application.service

import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.persistence.repository.MedKitAccessRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MedKitAccessService(
    private val repository: MedKitAccessRepository
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAccessible(userId: UUID, medKitIds: Collection<UUID>) {
        val distinctIds = medKitIds.toSet()
        val locked = repository.lockAccessible(userId, distinctIds)
        if (locked.size != distinctIds.size) {
            throw MedKitNotFound(distinctIds.first { it !in locked })
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun memberIds(medKitId: UUID): Set<UUID> = repository.memberIds(medKitId)
}
