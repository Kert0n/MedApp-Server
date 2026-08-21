package org.kert0n.medappserver.services.application

import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toSnapshots
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Снимок вызывающего: всё, что ему видно, одним ответом.
 *
 * Три чтения на весь ответ, сколько бы у человека ни было аптечек и пачек: упаковки, брони на
 * них, аптечки. Собирает сборщик из `api` — здесь только порядок вызовов.
 */
@Service
class UserApplicationService(
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val medKitService: MedKitService
) {

    @Transactional(readOnly = true)
    fun snapshot(userId: Uuid): UserSnapshotDTO {
        val packages = drugService.allOf(userId)
        val drugs = packages.toSnapshots(reservationService.onDrugs(packages.map { it.id }, userId), userId)
        return UserSnapshotDTO(id = userId, medKits = medKitService.allOfUser(userId).toDto(drugs))
    }
}
