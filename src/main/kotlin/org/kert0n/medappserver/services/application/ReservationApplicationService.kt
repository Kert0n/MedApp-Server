package org.kert0n.medappserver.services.application

import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.api.statedVersion
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrator.ReservationPlacement
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
    private val placement: ReservationPlacement
) {

    @Transactional(readOnly = true)
    fun ofUser(userId: Uuid): List<ReservationDTO> = reservationService.ofUser(userId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun read(userId: Uuid, drugId: Uuid): ReservationDTO = reservationService.get(userId, drugId).toDto()

    @Transactional
    fun create(userId: Uuid, request: ReservationCreateRequest): ReservationDTO =
        placement.place(request.drugId, userId, request.amount, statedVersion(request.version)).toDto()

    @Transactional
    fun changeTo(userId: Uuid, drugId: Uuid, request: ReservationPatchRequest): ReservationDTO =
        reservationService.changeTo(userId, drugId, request.amount, statedVersion(request.version)).toDto()

    @Transactional
    fun cancel(userId: Uuid, drugId: Uuid, version: Long?) =
        reservationService.cancel(userId, drugId, statedVersion(version))
}
