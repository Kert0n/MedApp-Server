package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.kert0n.medappserver.db.tables.Drugs
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.db.tables.Reservations
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationSnapshot
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата брони.
 *
 * Наружу — только доменные типы. Единицу величины приносит то же чтение, соединением с
 * упаковкой: бронь в «штуках вообще» смысла не имеет.
 */
@Component
class ReservationStore {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /**
     * Свои брони.
     *
     * Предиката членства здесь нет намеренно: бронь ссылается на членство составным ключом,
     * и её существование само по себе доказывает доступ.
     */
    fun findAllOfUser(userId: Uuid): List<Reservation> =
        reservationsWhere { Reservations.userId eq userId }.orderBy(Drugs.name).map { it.toDomain() }

    fun find(userId: Uuid, drugId: Uuid): Reservation? =
        reservationsWhere { (Reservations.userId eq userId) and (Reservations.drugId eq drugId) }
            .singleOrNull()?.toDomain()

    /**
     * Брони на перечисленные упаковки — чтобы ответить, сколько на пачку заявлено.
     *
     * Отдаёт чужие брони, поэтому предикат про вызывающего: видеть заявленное можно там, куда
     * есть доступ.
     */
    fun findAllOfDrugs(drugIds: Collection<Uuid>, userId: Uuid): List<Reservation> =
        if (drugIds.isEmpty()) emptyList()
        else reservationsWhere { (Reservations.drugId inList drugIds) and visibleTo(userId) }.map { it.toDomain() }

    /**
     * Заявленное на упаковки — снимками, а не голыми бронями.
     *
     * Упаковки приходят объектами: вызывающий их уже прочитал, и это и есть доказательство
     * доступа — отдельный предикат тут был бы второй копией того же правила.
     *
     * Версия снимка живёт в колонке рядом с упаковкой и принадлежит снимку, поэтому читает её
     * это хранилище, а не хранилище упаковок. Упаковка без броней тоже получает снимок: сумма
     * ноль, а версия у неё всё равно своя.
     */
    fun snapshotsOf(drugs: List<Drug>, userId: Uuid): Map<Uuid, ReservationSnapshot> {
        if (drugs.isEmpty()) return emptyMap()

        val ids = drugs.map { it.id }
        val versions = Drugs.select(Drugs.id, Drugs.reservationsVersion)
            .where { Drugs.id inList ids }
            .associate { it[Drugs.id] to it[Drugs.reservationsVersion] }
        val byDrug = findAllOfDrugs(ids, userId).groupBy { it.drugId }

        return ids.associateWith { id ->
            ReservationSnapshot.of(id, byDrug[id].orEmpty(), userId, versions.getValue(id))
        }
    }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /**
     * Пачка приходит доменным объектом, а не поднимается из чужого хранилища.
     *
     * Нужна от неё одна вещь — аптечка для составного ключа, — и она уже есть у вызывающего:
     * он эту пачку и прочитал. Рассогласовать копию с настоящей не даст сам ключ.
     */
    fun insert(reservation: Reservation, drug: Drug) {
        Reservations.insert {
            it[userId] = reservation.userId
            it[drugId] = reservation.drugId
            it[medKitId] = drug.medKitId
            it[amount] = reservation.amount.amount
        }
    }

    fun save(reservation: Reservation) {
        Reservations.update({ identityOf(reservation) }) { it[amount] = reservation.amount.amount }
    }

    fun delete(reservation: Reservation) {
        Reservations.deleteWhere { identityOf(reservation) }
    }

    /** Все брони на упаковку — когда упаковки не станет. */
    fun deleteOfDrug(drug: Drug) {
        Reservations.deleteWhere { Reservations.drugId eq drug.id }
    }

    /** Брони тех, кто аптечку не видит, — при удалении аптечки с переносом. */
    fun deleteInMedKitExcept(medKit: MedKit, accessibleUserIds: Set<Uuid>) {
        Reservations.deleteWhere {
            (Reservations.medKitId eq medKit.id) and (Reservations.userId notInList accessibleUserIds)
        }
    }

    /** То же для одной переехавшей упаковки. */
    fun deleteOfDrugExcept(drug: Drug, accessibleUserIds: Set<Uuid>) {
        Reservations.deleteWhere {
            (Reservations.drugId eq drug.id) and (Reservations.userId notInList accessibleUserIds)
        }
    }

    /** Тождество брони — пара «человек и упаковка». */
    private fun identityOf(reservation: Reservation): Op<Boolean> =
        (Reservations.userId eq reservation.userId) and (Reservations.drugId eq reservation.drugId)

    private fun visibleTo(userId: Uuid): Op<Boolean> =
        Reservations.medKitId inSubQuery MedKitMemberships
            .select(MedKitMemberships.medKitId)
            .where { MedKitMemberships.userId eq userId }

    /** Брони вместе с упаковкой и её единицей, отобранные условием. */
    private fun reservationsWhere(condition: () -> Op<Boolean>): Query =
        withDrug.selectAll().where(condition())

    private val withDrug: Join
        get() = Reservations
            .join(Drugs, JoinType.INNER, Reservations.drugId, Drugs.id)
            .join(QuantityUnits, JoinType.INNER, Drugs.quantityUnitId, QuantityUnits.id)

    /** Единица величины лежит у упаковки: бронь в «штуках вообще» смысла не имеет. */
    private fun ResultRow.toDomain(): Reservation = Reservation(
        userId = this[Reservations.userId],
        drugId = this[Reservations.drugId],
        amount = Quantity(
            this[Reservations.amount],
            QuantityUnit(this[QuantityUnits.id], this[QuantityUnits.name])
        )
    )
}
