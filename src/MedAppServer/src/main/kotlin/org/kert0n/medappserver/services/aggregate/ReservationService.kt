package org.kert0n.medappserver.services.aggregate

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату брони.
 *
 * Операции те же, что у владельца: завести, изменить, отменить. Ограничения «не больше
 * остатка» нет — сколько оставить, решает он, а не сервер.
 *
 * Доступ к упаковке проверяется первым: бронировать можно только то, что видно. Единицу
 * величины приносит `DrugService` — бронь в «штуках вообще» смысла не имеет.
 *
 * Оба основания решения удерживаются до коммита: это правило **о брони**, поэтому живёт здесь,
 * а не у вызывающего. Иначе один и тот же сценарий пришлось бы повторять всем, кто заводит
 * бронь, — и повторяют его двое: контроллер брони и синхронизация упаковки.
 *
 * Команды возвращают **записанное** состояние, а не посчитанное: версию двигает Hibernate, и
 * доменная копия, сделанная до записи, несёт устаревший токен.
 */
@Service
class ReservationService(
    private val reservations: ReservationStore,
    private val drugs: DrugService,
    private val medKits: MedKitService
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /** Все брони вызывающего — одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun ofUser(userId: UUID): List<Reservation> {
        logger.debug("Reading reservations of user {}", userId)
        return reservations.findAllOfUser(userId)
    }

    /** Бронь или `null`, если её нет. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun find(userId: UUID, drugId: UUID): Reservation? = reservations.find(userId, drugId)

    /** Бронь или 404. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun require(userId: UUID, drugId: UUID): Reservation = find(userId, drugId) ?: throw NoSuchReservation()

    /**
     * Бронь в том состоянии, по которому решал клиент.
     *
     * Сначала существование, потом версия: чужой брони не существует для вызывающего вовсе.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun requireAt(userId: UUID, drugId: UUID, expectedVersion: Long): Reservation =
        require(userId, drugId).also { it.requireVersion(expectedVersion) }

    /** Брони на перечисленные упаковки — чтобы ответить, сколько на пачку заявлено. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun onDrugs(drugIds: Collection<UUID>): List<Reservation> = reservations.findAllOfDrugs(drugIds)

    // ── Команды ──────────────────────────────────────────────────────────────────

    /**
     * Заведение брони.
     *
     * Право проверяет чтение: `drugs.require` фильтрует по членству, и недоступная пачка сюда
     * не доходит. Версией это не проверяется — она отвечает за состояние своей сущности, а не
     * за чьи-то права, и удержание состава аптечки отвергало бы команду из-за постороннего
     * вступления.
     *
     * Что бронь не переживёт утрату доступа, держат правила выхода и переезда, а под ними —
     * ключ на членство: он же не даёт вставке разойтись с одновременным выходом.
     */
    @Transactional(propagation = MANDATORY)
    fun create(userId: UUID, drugId: UUID, amount: BigDecimal): Reservation {
        logger.debug("Creating reservation of user {} on drug {}", userId, drugId)

        val drug = drugs.require(drugId, userId)
        if (reservations.find(userId, drugId) != null) throw ReservationAlreadyExists()

        val reservation = Reservation(userId, drugId, Quantity(amount, drug.quantity.unit))
        return reservations.insert(reservation)
    }

    @Transactional(propagation = MANDATORY)
    fun changeTo(userId: UUID, drugId: UUID, amount: BigDecimal, expectedVersion: Long): Reservation {
        logger.debug("Changing reservation of user {} on drug {}", userId, drugId)

        val current = requireAt(userId, drugId, expectedVersion)
        return reservations.save(current.changeTo(Quantity(amount, current.amount.unit)))
    }

    /**
     * Снятие броней, потерявших доступ.
     *
     * Массово, а не обходом: у аптечки со ста пачками поднимать каждую ради одной строки
     * незачем. Кто именно потерял доступ, решает вызывающий — состав знает он.
     */
    @Transactional(propagation = MANDATORY)
    fun dropOfUserInMedKit(userId: UUID, medKitId: UUID) {
        logger.debug("Dropping reservations of user {} in medkit {}", userId, medKitId)
        reservations.deleteOfUserInMedKit(userId, medKitId)
    }

    /** Брони всех, кто целевую аптечку не видит, — при удалении с переносом. */
    @Transactional(propagation = MANDATORY)
    fun dropInMedKitExcept(medKitId: UUID, accessibleUserIds: Set<UUID>) {
        logger.debug("Dropping reservations in medkit {} outside {} users", medKitId, accessibleUserIds.size)
        reservations.deleteInMedKitExcept(medKitId, accessibleUserIds)
    }

    /** То же для одной переехавшей упаковки. */
    @Transactional(propagation = MANDATORY)
    fun dropOnDrugExcept(drugId: UUID, accessibleUserIds: Set<UUID>) {
        logger.debug("Dropping reservations on drug {} outside {} users", drugId, accessibleUserIds.size)
        reservations.deleteOfDrugExcept(drugId, accessibleUserIds)
    }

    /** Отмена — это удаление: брони с нулём не бывает. */
    @Transactional(propagation = MANDATORY)
    fun cancel(userId: UUID, drugId: UUID, expectedVersion: Long) {
        logger.debug("Cancelling reservation of user {} on drug {}", userId, drugId)

        requireAt(userId, drugId, expectedVersion)
        reservations.delete(userId, drugId)
    }
}
