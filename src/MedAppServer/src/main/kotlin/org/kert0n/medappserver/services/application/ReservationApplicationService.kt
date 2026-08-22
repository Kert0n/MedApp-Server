package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.ReservationDTO
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
    fun create(userId: Uuid, drugId: Uuid, amount: BigDecimal, version: Long?): ReservationDTO {
        statedSnapshot(drugId, userId, version)
        return placement.place(drugId, userId, amount).toDto()
    }

    @Transactional
    fun changeTo(userId: Uuid, drugId: Uuid, amount: BigDecimal, version: Long?): ReservationDTO {
        statedSnapshot(drugId, userId, version)
        return reservationService.changeTo(userId, drugId, amount).toDto()
    }

    @Transactional
    fun cancel(userId: Uuid, drugId: Uuid, version: Long?) {
        statedSnapshot(drugId, userId, version)
        reservationService.cancel(userId, drugId)
    }

    /**
     * Сверяет версию картины броней, а не отдельной брони.
     *
     * Токен принадлежит картине: решая, сколько заявить, человек смотрит на общую сумму, и
     * менять свою долю по устаревшей картине — то же самое, что решать вслепую.
     */
    private fun statedSnapshot(drugId: Uuid, userId: Uuid, version: Long?) {
        requireStated(version, reservationService.snapshotOn(drugId, userId).version)
    }
}
