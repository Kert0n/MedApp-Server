package org.kert0n.medappserver.db.store

import jakarta.persistence.EntityManager
import java.util.UUID
import org.kert0n.medappserver.db.model.ReservationData
import org.kert0n.medappserver.db.model.ReservationKey
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.ReservationRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.Reservation
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата брони.
 *
 * Наружу — только доменные типы: сервисы не видят ни строк, ни репозиториев. Единицу величины
 * приносит то же чтение, соединением.
 */
@Component
class ReservationStore(
    private val reservations: ReservationRepository,
    private val drugs: DrugRepository,
    private val users: UserRepository,
    private val entityManager: EntityManager
) {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun findAllOfUser(userId: UUID): List<Reservation> = reservations.findAllOfUser(userId).map { it.toDomain() }

    fun find(userId: UUID, drugId: UUID): Reservation? = reservations.findOne(userId, drugId)?.toDomain()

    /** Брони на перечисленные упаковки — чтобы ответить, сколько на пачку заявлено. */
    fun findAllOfDrugs(drugIds: Collection<UUID>): List<Reservation> =
        if (drugIds.isEmpty()) emptyList() else reservations.findAllOfDrugs(drugIds).map { it.toDomain() }

    // ── Команды ──────────────────────────────────────────────────────────────────

/**
     * Возвращает записанное состояние, а не то, что просили записать.
     *
     * Разница в версии: её двигает Hibernate на flush, и доменная копия, посчитанная до записи,
     * про это не знает. Вернуть её значило бы выдать клиенту тег, по которому его же следующая
     * команда получит отказ.
     */
    fun insert(reservation: Reservation): Reservation {
        val drug = drugs.findByIdOrNull(reservation.drugId)
            ?: error("Упаковка ${reservation.drugId} исчезла во время записи брони")
        val user = users.findByIdOrNull(reservation.userId)
            ?: error("Пользователь ${reservation.userId} исчез во время записи брони")

        // persist, а не save: у брони присвоенный составной ключ, и save пошёл бы через merge —
        // искать несуществующую строку и сохранять копию, теряя связь с управляемой упаковкой.
        val row = ReservationData(
            reservationKey = ReservationKey(reservation.userId, reservation.drugId),
            userData = user,
            drugData = drug,
            amount = reservation.amount.amount
        )
        entityManager.persist(row)
        entityManager.flush()
        return row.toDomain()
    }

    /** Лишнего чтения нет: в той же транзакции строка уже в persistence context. */
    fun save(reservation: Reservation): Reservation {
        val row = managed(reservation.userId, reservation.drugId)
        row.amount = reservation.amount.amount
        reservations.save(row)
        entityManager.flush()
        return row.toDomain()
    }

    fun delete(userId: UUID, drugId: UUID) {
        reservations.findByIdOrNull(ReservationKey(userId, drugId))?.let { reservations.delete(it) }
    }

    /** Брони участника во всех упаковках аптечки — при выходе из неё. */
    fun deleteOfUserInMedKit(userId: UUID, medKitId: UUID) {
        reservations.deleteOfUserInMedKit(userId, medKitId)
    }

    /** Брони тех, кто аптечку не видит, — при удалении аптечки с переносом. */
    fun deleteInMedKitExcept(medKitId: UUID, accessibleUserIds: Set<UUID>) {
        reservations.deleteInMedKitExcept(medKitId, accessibleUserIds)
    }

    /** То же для одной переехавшей упаковки. */
    fun deleteOfDrugExcept(drugId: UUID, accessibleUserIds: Set<UUID>) {
        reservations.deleteOfDrugExcept(drugId, accessibleUserIds)
    }

    /** Единица величины лежит у упаковки: бронь в «штуках вообще» смысла не имеет. */
    private fun ReservationData.toDomain(): Reservation = Reservation(
        userId = reservationKey.userId,
        drugId = reservationKey.drugId,
        amount = Quantity(amount, drugData.quantityUnit.toDomain()),
        version = version
    )

    private fun managed(userId: UUID, drugId: UUID): ReservationData =
        reservations.findByIdOrNull(ReservationKey(userId, drugId))
            ?: error("Бронь $userId/$drugId исчезла во время записи")
}
