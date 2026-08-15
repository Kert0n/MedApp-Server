package org.kert0n.medappserver.domain.medkit

import org.kert0n.medappserver.domain.error.MedKitNotFound
import java.util.UUID

data class MedKitMembership(
    val medKitId: UUID,
    val userId: UUID
)

class MedKit private constructor(
    val id: UUID,
    memberships: Collection<MedKitMembership>
) {
    private val memberIds = memberships.mapTo(linkedSetOf(), MedKitMembership::userId)

    val members: Set<UUID> get() = memberIds.toSet()

    fun requireAccess(userId: UUID) {
        if (userId !in memberIds) throw MedKitNotFound(id)
    }

    fun join(userId: UUID): Boolean = memberIds.add(userId)

    fun leave(userId: UUID): LeaveMedKitDecision {
        requireAccess(userId)
        memberIds.remove(userId)
        return LeaveMedKitDecision(deleteMedKit = memberIds.isEmpty())
    }

    companion object {
        fun create(ownerId: UUID, id: UUID = UUID.randomUUID()): MedKit =
            MedKit(id, listOf(MedKitMembership(id, ownerId)))

        fun restore(id: UUID, memberIds: Collection<UUID>): MedKit =
            MedKit(id, memberIds.map { MedKitMembership(id, it) })
    }
}

data class LeaveMedKitDecision(
    val deleteMedKit: Boolean
)
