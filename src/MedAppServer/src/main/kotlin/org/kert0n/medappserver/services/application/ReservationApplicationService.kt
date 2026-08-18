package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Всё, что клиент делает со своей бронью.
 *
 * Заведение брони трогает три агрегата, изменение — один, и снаружи эта разница не видна:
 * контроллер брони разговаривает только отсюда.
 */
@Service
class ReservationApplicationService(
    private val reservationService: ReservationService,
    private val drugService: DrugService,
    private val medKitService: MedKitService
) {

    private val logger = LoggerFactory.getLogger(ReservationApplicationService::class.java)

    @Transactional(readOnly = true)
    fun ofUser(userId: UUID): List<ReservationDTO> = reservationService.ofUser(userId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun read(userId: UUID, drugId: UUID): ReservationDTO = reservationService.require(userId, drugId).toDto()

    /**
     * Заведение брони: решение принимается по упаковке и по составу её аптечки.
     *
     * Оба удерживаются до коммита. Иначе бронь появляется у того, кто в этот момент вышел из
     * аптечки, или на пачке, которая успела уехать в недоступную, — и живёт там, потому что
     * уборщики броней отработали раньше, чем она была заведена.
     */
    @Transactional
    fun create(userId: UUID, drugId: UUID, amount: BigDecimal): ReservationDTO {
        logger.debug("Creating reservation of user {} on drug {}", userId, drugId)
        val drug = drugService.require(drugId, userId)
        drugService.requireUnchanged(drug)
        medKitService.requireUnchanged(medKitService.requireAccessible(drug.medKitId, userId))
        return reservationService.create(userId, drugId, amount).toDto()
    }

    @Transactional
    fun changeTo(userId: UUID, drugId: UUID, amount: BigDecimal, expectedVersion: Long): ReservationDTO =
        reservationService.changeTo(userId, drugId, amount, expectedVersion).toDto()

    @Transactional
    fun cancel(userId: UUID, drugId: UUID, expectedVersion: Long) =
        reservationService.cancel(userId, drugId, expectedVersion)
}
