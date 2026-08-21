package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Всё, что клиент делает со своей бронью.
 *
 * Тонкий: переводит в DTO и владеет транзакцией. Правила заведения — у самой брони, потому что
 * они о ней, а не о том, кто её заказал.
 */
@Service
class ReservationApplicationService(
    private val reservationService: ReservationService,
    private val drugService: DrugService
) {

    @Transactional(readOnly = true)
    fun ofUser(userId: UUID): List<ReservationDTO> = reservationService.ofUser(userId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun read(userId: UUID, drugId: UUID): ReservationDTO = reservationService.require(userId, drugId).toDto()

    @Transactional
    fun create(userId: UUID, drugId: UUID, amount: BigDecimal): ReservationDTO =
        // Упаковка читается здесь: бронировать можно только то, что видно, и прочитанная
        // пачка — доказательство этого. Она же приносит единицу величины.
        reservationService.create(drugService.require(drugId, userId), userId, amount).toDto()

    @Transactional
    fun changeTo(userId: UUID, drugId: UUID, amount: BigDecimal): ReservationDTO =
        reservationService.changeTo(reservationService.require(userId, drugId), amount).toDto()

    @Transactional
    fun cancel(userId: UUID, drugId: UUID) =
        reservationService.cancel(reservationService.require(userId, drugId))
}
