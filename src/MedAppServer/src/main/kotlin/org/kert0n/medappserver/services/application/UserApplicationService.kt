package org.kert0n.medappserver.services.application

import java.util.UUID
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Снимок вызывающего: всё, что ему видно, одним ответом.
 *
 * Число запросов постоянно: аптечки одним чтением, доступные упаковки вторым, брони на них
 * третьим. От того, сколько у человека аптечек и пачек, оно не зависит.
 */
@Service
class UserApplicationService(
    private val medKitService: MedKitService,
    private val drugs: DrugApplicationService
) {

    @Transactional(readOnly = true)
    fun snapshot(userId: UUID): UserSnapshotDTO {
        val drugsByMedKit = drugs.accessibleTo(userId).groupBy { it.medKitId }
        val medKits = medKitService.allOfUser(userId)
            .map { it.toDto(drugsByMedKit[it.id].orEmpty().toSet()) }
            .toSet()
        return UserSnapshotDTO(id = userId, medKits = medKits)
    }
}
