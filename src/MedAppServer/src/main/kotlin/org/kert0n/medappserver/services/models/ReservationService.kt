package org.kert0n.medappserver.services.models

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату брони.
 *
 * Бронь принадлежит своему владельцу, и правила здесь ровно те, что у владельца: завести,
 * изменить, отменить. Ограничения «не больше остатка» нет и не будет — сколько из своей брони
 * оставить, решает человек, а не сервер.
 *
 * Доступ к упаковке проверяется до всего остального: бронировать можно только то, что видно.
 * Величина брони собирается в единице упаковки — единицу приносит `DrugService`, потому что
 * бронь в «штуках вообще» смысла не имеет.
 */
@Service
class ReservationService(
    private val reservations: ReservationStore,
    private val drugs: DrugService
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /** Все брони вызывающего — одним запросом, сколько бы упаковок он ни держал. */
    @Transactional(readOnly = true)
    fun ofUser(userId: UUID): List<Reservation> {
        logger.debug("Reading reservations of user {}", userId)
        return reservations.findAllOfUser(userId)
    }

    /** Бронь или `null`, если её нет. */
    @Transactional(readOnly = true)
    fun find(userId: UUID, drugId: UUID): Reservation? = reservations.find(userId, drugId)

    /** Бронь или 404. */
    @Transactional(readOnly = true)
    fun require(userId: UUID, drugId: UUID): Reservation = find(userId, drugId) ?: throw NoSuchReservation()

    /**
     * Брони на перечисленные упаковки.
     *
     * Нужны, чтобы ответить «сколько на эту пачку заявлено». Чтение упаковки при этом трогает
     * чужой агрегат — принято сознательно: цена одного запроса ниже, чем цена третьего типа на
     * одно понятие.
     */
    @Transactional(readOnly = true)
    fun onDrugs(drugIds: Collection<UUID>): List<Reservation> = reservations.findAllOfDrugs(drugIds)

    // ── Команды ──────────────────────────────────────────────────────────────────

    @Transactional
    fun create(userId: UUID, drugId: UUID, amount: BigDecimal): Reservation {
        logger.debug("Creating reservation of user {} on drug {}", userId, drugId)

        val drug = drugs.require(drugId, userId)
        if (reservations.find(userId, drugId) != null) throw ReservationAlreadyExists()

        val reservation = Reservation(userId, drugId, Quantity(amount, drug.quantity.unit))
        reservations.insert(reservation)
        return reservation
    }

    @Transactional
    fun changeTo(userId: UUID, drugId: UUID, amount: BigDecimal): Reservation {
        logger.debug("Changing reservation of user {} on drug {}", userId, drugId)

        val current = require(userId, drugId)
        val changed = current.changeTo(Quantity(amount, current.amount.unit))
        reservations.save(changed)
        return changed
    }

    /** Отмена — это удаление: брони с нулём не бывает. */
    @Transactional
    fun cancel(userId: UUID, drugId: UUID) {
        logger.debug("Cancelling reservation of user {} on drug {}", userId, drugId)

        require(userId, drugId)
        reservations.delete(userId, drugId)
    }
}
