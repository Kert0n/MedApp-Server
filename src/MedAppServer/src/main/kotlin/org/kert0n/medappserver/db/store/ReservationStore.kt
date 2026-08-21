package org.kert0n.medappserver.db.store

import jakarta.persistence.EntityManager
import java.util.UUID
import org.kert0n.medappserver.db.model.ReservationData
import org.kert0n.medappserver.db.model.ReservationKey
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.ReservationRepository
import org.hibernate.exception.ConstraintViolationException
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.ReservationAlreadyExists
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
    private val entityManager: EntityManager
) {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun findAllOfUser(userId: UUID): List<Reservation> = reservations.findAllOfUser(userId).map { it.toDomain() }

    fun find(userId: UUID, drugId: UUID): Reservation? = reservations.findOne(userId, drugId)?.toDomain()

    /**
     * Брони на перечисленные упаковки — чтобы ответить, сколько на пачку заявлено.
     *
     * Отдаёт чужие брони, поэтому предикат про вызывающего: видеть заявленное можно там, куда
     * есть доступ. Своё членство доказывать не нужно — его доказывает существование строки.
     */
    fun findAllOfDrugs(drugIds: Collection<UUID>, userId: UUID): List<Reservation> =
        if (drugIds.isEmpty()) emptyList() else reservations.findAllOfDrugs(drugIds, userId).map { it.toDomain() }

    // ── Команды ──────────────────────────────────────────────────────────────────

    fun insert(reservation: Reservation) {
        val drug = drugs.findByIdOrNull(reservation.drugId)
            ?: error("Упаковка ${reservation.drugId} исчезла во время записи брони")

        // persist, а не save: у брони присвоенный составной ключ, и save пошёл бы через merge —
        // искать несуществующую строку и сохранять копию, теряя связь с управляемой упаковкой.
        entityManager.persist(
            ReservationData(
                reservationKey = ReservationKey(reservation.userId, reservation.drugId),
                // Аптечка берётся у самой пачки: рассогласовать копию с настоящей нельзя даже так.
                medKitId = drug.medKit.id,
                amount = reservation.amount.amount,
                // Связь только на чтение: на вставку не влияет, но избавляет от лишнего чтения,
                // когда строку тут же переводят в доменный вид — единица величины лежит у пачки.
                drugData = drug
            )
        )
        // Флаш здесь, а не на коммите: пока запись не дошла до базы, о том, что такая бронь уже
        // есть, знает только база, — а на коммите переводить нарушение ключа в правило уже некому.
        flushTranslating(RESERVATION_KEY) { ReservationAlreadyExists() }
    }

    /** Лишнего запроса нет: в той же транзакции строка уже в persistence context. */
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
        amount = Quantity(amount, drugData!!.quantityUnit.toDomain())
    )

    private fun managed(userId: UUID, drugId: UUID): ReservationData =
        reservations.findByIdOrNull(ReservationKey(userId, drugId))
            ?: error("Бронь $userId/$drugId исчезла во время записи")

    /**
     * Переводит нарушение именованного ограничения в доменный отказ.
     *
     * Имя ключа — единственное, чем гонка отличается от поломки: без него пришлось бы либо
     * читать перед записью и всё равно проиграть гонку, либо отдавать наружу 500.
     */
    private fun flushTranslating(constraint: String, refusal: () -> DomainRuleViolated) {
        try {
            entityManager.flush()
        } catch (violation: ConstraintViolationException) {
            if (violation.constraintName?.lowercase() == constraint) throw refusal()
            throw violation
        }
    }

    private companion object {
        const val RESERVATION_KEY = "reservations_pkey"
    }
}
