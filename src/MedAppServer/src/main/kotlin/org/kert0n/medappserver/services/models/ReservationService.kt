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
 * Операции те же, что у владельца: завести, изменить, отменить. Ограничения «не больше
 * остатка» нет — сколько оставить, решает он, а не сервер.
 *
 * Доступ к упаковке проверяется первым: бронировать можно только то, что видно. Единицу
 * величины приносит `DrugService` — бронь в «штуках вообще» смысла не имеет.
 */
@Service
class ReservationService(
    private val reservations: ReservationStore,
    private val drugs: DrugService
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /** Все брони вызывающего — одним запросом. */
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
     * Бронь в том состоянии, по которому решал клиент.
     *
     * Сначала существование, потом версия: чужой брони не существует для вызывающего вовсе.
     */
    @Transactional(readOnly = true)
    fun requireAt(userId: UUID, drugId: UUID, expectedVersion: Long): Reservation =
        require(userId, drugId).also { it.requireVersion(expectedVersion) }

    /** Брони на перечисленные упаковки — чтобы ответить, сколько на пачку заявлено. */
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
    fun changeTo(userId: UUID, drugId: UUID, amount: BigDecimal, expectedVersion: Long): Reservation {
        logger.debug("Changing reservation of user {} on drug {}", userId, drugId)

        val current = requireAt(userId, drugId, expectedVersion)
        val changed = current.changeTo(Quantity(amount, current.amount.unit))
        reservations.save(changed)
        return changed
    }

    /** Отмена — это удаление: брони с нулём не бывает. */
    @Transactional
    fun cancel(userId: UUID, drugId: UUID, expectedVersion: Long) {
        logger.debug("Cancelling reservation of user {} on drug {}", userId, drugId)

        requireAt(userId, drugId, expectedVersion)
        reservations.delete(userId, drugId)
    }
}
