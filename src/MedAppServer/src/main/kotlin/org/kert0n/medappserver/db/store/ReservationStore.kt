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
 * Наружу отдаёт и принимает только доменные типы: ни один сервис не видит ни строк, ни
 * репозиториев. Величина брони собирается в единице своей упаковки — единицу приносит то же
 * чтение, соединением.
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

    fun insert(reservation: Reservation) {
        val drug = drugs.findByIdOrNull(reservation.drugId)
            ?: error("Упаковка ${reservation.drugId} исчезла во время записи брони")
        val user = users.findByIdOrNull(reservation.userId)
            ?: error("Пользователь ${reservation.userId} исчез во время записи брони")

        // Именно persist, а не save: у брони присвоенный составной ключ, и `save` пошёл бы
        // через merge — искать несуществующую строку и сохранять копию, теряя связь с уже
        // управляемой упаковкой.
        entityManager.persist(
            ReservationData(
                reservationKey = ReservationKey(reservation.userId, reservation.drugId),
                userData = user,
                drugData = drug,
                amount = reservation.amount.amount
            )
        )
    }

    /**
     * Записывает состояние в уже загруженную строку.
     *
     * Лишнего запроса тут нет: внутри той же транзакции строка лежит в persistence context, и
     * поиск по ключу берёт её оттуда.
     */
    fun save(reservation: Reservation) {
        val row = managed(reservation.userId, reservation.drugId)
        row.amount = reservation.amount.amount
        reservations.save(row)
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
        amount = Quantity(amount, drugData.quantityUnit.toDomain())
    )

    private fun managed(userId: UUID, drugId: UUID): ReservationData =
        reservations.findByIdOrNull(ReservationKey(userId, drugId))
            ?: error("Бронь $userId/$drugId исчезла во время записи")
}
