package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Изменение существующей брони под удерживаемым доступом к её упаковке.
 *
 * `content` здесь зовётся ради удержания и перечитывания упаковки, а его результат не нужен:
 * бронь ищется по паре «участник и упаковка», и эту форму `ReservationService` уже умеет.
 */
@Service
class ReservationChanging(
    private val access: DrugCommandAccess,
    private val reservations: ReservationService
) {

    @Transactional(propagation = MANDATORY)
    fun changeTo(userId: Uuid, drugId: Uuid, amount: BigDecimal, stated: Long): Reservation {
        access.content(drugId, userId)
        return reservations.changeTo(userId, drugId, amount, stated)
    }

    @Transactional(propagation = MANDATORY)
    fun cancel(userId: Uuid, drugId: Uuid, stated: Long) {
        access.content(drugId, userId)
        reservations.cancel(userId, drugId, stated)
    }
}
