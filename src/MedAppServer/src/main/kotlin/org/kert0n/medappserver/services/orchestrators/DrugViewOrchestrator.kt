package org.kert0n.medappserver.services.orchestrators

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Упаковка вместе с тем, сколько на неё заявлено.
 *
 * Сама упаковка про брони не знает — и не должна. Но клиенту показать «в пачке тридцать, на неё
 * заявлено сорок» полезно, и собрать это можно только над двумя агрегатами сразу. Поэтому
 * сборка живёт здесь, а не в `DrugService`: тот про чужие агрегаты не спрашивает.
 *
 * Заявленное **может превышать остаток**, и это не ошибка отображения, а нормальное состояние
 * системы: чью бронь ужать, решает её владелец.
 *
 * Брони читаются одним запросом на весь набор упаковок, сколько бы их ни было: дорого
 * обращение в базу, а не объект в памяти.
 */
@Service
class DrugViewOrchestrator(
    private val drugService: DrugService,
    private val reservationService: ReservationService
) {

    @Transactional(readOnly = true)
    fun view(drugId: UUID, userId: UUID): DrugDTO = views(listOf(drugService.require(drugId, userId))).single()

    @Transactional(readOnly = true)
    fun viewsOfMedKit(medKitId: UUID): List<DrugDTO> = views(drugService.ofMedKit(medKitId))

    @Transactional(readOnly = true)
    fun viewsAccessibleTo(userId: UUID): List<DrugDTO> = views(drugService.accessibleTo(userId))

    private fun views(drugs: List<Drug>): List<DrugDTO> {
        val reservedPerDrug = reservationService.onDrugs(drugs.map { it.id })
            .groupBy { it.drugId }
            .mapValues { (_, reservations) -> reservations.sumOf { it.amount.amount } }

        return drugs.map { it.toDto(reservedPerDrug[it.id] ?: BigDecimal.ZERO) }
    }
}
