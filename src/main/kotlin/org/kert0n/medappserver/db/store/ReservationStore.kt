package org.kert0n.medappserver.db.store

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.kert0n.medappserver.db.tables.Drugs
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.db.tables.Reservations
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationSnapshot
import org.kert0n.medappserver.domain.StaleVersion
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата брони.
 *
 * Наружу — только доменные типы. Единицу величины приносит то же чтение, соединением с
 * упаковкой: бронь в «штуках вообще» смысла не имеет.
 *
 * Правила обращения — в `Access.kt`, одним списком на весь пакет. Коротко: чтения называют
 * вызывающего и скоупятся запросом, команды принимают агрегат, а разделы ниже подписаны потому,
 * что обещание относится к публичной поверхности, а не к приватным помощникам.
 */
@Component
class ReservationStore {

    // ── Чтения: принимают вызывающего, скоуп накладывает запрос ──────────────────

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

        val byId = drugs.associateBy { it.id }
        val ids = byId.keys.toList()
        val versions = Drugs.select(Drugs.id, Drugs.reservationsVersion)
            .where { Drugs.id inList ids }
            .associate { it[Drugs.id] to it[Drugs.reservationsVersion] }
        val byDrug = findAllOfDrugs(ids, userId).groupBy { it.drugId }

        return ids.associateWith { id ->
            ReservationSnapshot.of(byId.getValue(id), byDrug[id].orEmpty(), userId, versions.getValue(id))
        }
    }

    /**
     * Снимок одной упаковки — только если она доступна вызывающему.
     *
     * Предикат здесь стоит, хотя упаковка и приходит агрегатом: чтения скоупятся запросом, и
     * держать этот раздел на исключениях означало бы читать его с оглядкой. Заодно он ловит
     * пачку, которую успели удалить, — `null` отвечает одинаково на чужую и на исчезнувшую.
     */
    fun snapshotOf(drug: Drug, userId: Uuid): ReservationSnapshot? {
        val version = Drugs.select(Drugs.reservationsVersion)
            .where { (Drugs.id eq drug.id) and Drugs.medKitId.inMedKitsOf(userId) }
            .singleOrNull()?.get(Drugs.reservationsVersion)
            ?: return null
        return ReservationSnapshot.of(drug, findAllOfDrugs(listOf(drug.id), userId), userId, version)
    }

    // ── Команды: принимают агрегат — доступ к нему уже доказан ───────────────────

    /**
     * Пачка приходит доменным объектом, а не поднимается из чужого хранилища.
     *
     * Нужна от неё одна вещь — аптечка для составного ключа, — и она уже есть у вызывающего:
     * он эту пачку и прочитал. Рассогласовать копию с настоящей не даст сам ключ.
     */
    fun insert(reservation: Reservation, drug: Drug, stated: Long) {
        shiftSnapshot(drug.id, by = reservation.amount.amount, stated = stated)
        translatingConstraints {
            Reservations.insert {
                it[userId] = reservation.userId
                it[drugId] = reservation.drugId
                it[medKitId] = drug.medKitId
                it[amount] = reservation.amount.amount
            }
        }
    }

    fun save(reservation: Reservation, was: Reservation, stated: Long) {
        shiftSnapshot(reservation.drugId, by = reservation.amount.amount - was.amount.amount, stated = stated)
        Reservations.update({ identityOf(reservation) }) { it[amount] = reservation.amount.amount }
    }

    fun delete(reservation: Reservation, stated: Long) {
        shiftSnapshot(reservation.drugId, by = reservation.amount.amount.negate(), stated = stated)
        Reservations.deleteWhere { identityOf(reservation) }
    }

    /**
     * Все брони на упаковку — когда упаковки не станет.
     *
     * Снимок не пересчитывается: строка упаковки уходит следом, и пересчитывать нечего.
     */
    fun deleteOfDrug(drug: Drug) {
        Reservations.deleteWhere { Reservations.drugId eq drug.id }
    }

    /** Брони тех, кто аптечку не видит, — при удалении аптечки с переносом. */
    fun deleteInMedKitExcept(medKit: MedKit, accessibleUserIds: Set<Uuid>) {
        val touched = Reservations
            .select(Reservations.drugId)
            .where { (Reservations.medKitId eq medKit.id) and (Reservations.userId notInList accessibleUserIds) }
            .map { it[Reservations.drugId] }
            .distinct()

        Reservations.deleteWhere {
            (Reservations.medKitId eq medKit.id) and (Reservations.userId notInList accessibleUserIds)
        }
        recountSnapshots(touched)
    }

    /** То же для одной переехавшей упаковки. */
    fun deleteOfDrugExcept(drug: Drug, accessibleUserIds: Set<Uuid>) {
        Reservations.deleteWhere {
            (Reservations.drugId eq drug.id) and (Reservations.userId notInList accessibleUserIds)
        }
        recountSnapshots(listOf(drug.id))
    }

    // ── Внутреннее: помощники запросов и перенос строк ───────────────────────────
    //
    // Обещания разделов выше — про публичную поверхность. Здесь работают уже внутри доказанного
    // чтения или доказанной команды и берут то, что из агрегата достали: идентификатор, дельту,
    // набор участников. Это не исключение из правила, а его область действия.

    /** Чужие брони видно там, куда есть доступ: аптечка у брони своя, отдельным полем. */
    private fun visibleTo(userId: Uuid): Op<Boolean> = Reservations.medKitId.inMedKitsOf(userId)

    /** Брони вместе с упаковкой и её единицей, отобранные условием. */
    private fun reservationsWhere(condition: () -> Op<Boolean>): Query =
        withDrug.selectAll().where(condition())

    private val withDrug: Join
        get() = Reservations
            .join(Drugs, JoinType.INNER, Reservations.drugId, Drugs.id)
            .join(QuantityUnits, JoinType.INNER, Drugs.quantityUnitId, QuantityUnits.id)

    /**
     * Сдвигает снимок броней упаковки под предикатом его версии.
     *
     * Проверка и изменение — один оператор: ноль задетых строк значит, что картину успели
     * поменять после того, как её прочитали. Стоит перед правкой самой брони, чтобы
     * проигравший остановился раньше, чем тронет строку.
     *
     * Сумма ведётся дельтой, а не пересчётом: тот, кто меняет бронь, своё изменение знает.
     */
    private fun shiftSnapshot(drugId: Uuid, by: BigDecimal, stated: Long) {
        val moved = Drugs.update({
            (Drugs.id eq drugId) and (Drugs.reservationsVersion eq stated)
        }) {
            it[reservationsTotal] = Drugs.reservationsTotal + by
            it[reservationsVersion] = stated + 1
        }
        if (moved == 0) throw StaleVersion()
    }

    /**
     * Пересчёт после массового снятия.
     *
     * Клиент тут ничего не предъявляет: снимает не он, а сервер — при выходе участника или
     * переезде пачки. Предъявлять нечего, поэтому и предиката нет; версия всё равно двигается,
     * чтобы чужое представление о картине устарело.
     */
    private fun recountSnapshots(drugIds: Collection<Uuid>) {
        if (drugIds.isEmpty()) return

        // Сумму для каждой строки считает сам оператор — коррелированным подзапросом по
        // упаковке. Раньше здесь стоял цикл, по `UPDATE` на пачку: удаление аптечки со ста
        // упаковками стоило 109 запросов вместо 10, и заметить это было нечем.
        Drugs.update({ Drugs.id inList drugIds }) {
            // Броней может не остаться вовсе. Агрегат и на пустом наборе возвращает строку, но
            // с `NULL` внутри, а колонка его не терпит — отсюда `COALESCE` внутри подзапроса.
            it[reservationsTotal] = Reservations
                .select(Coalesce(Reservations.amount.sum(), decimalLiteral(BigDecimal.ZERO)))
                .where { Reservations.drugId eq Drugs.id }
            it[reservationsVersion] = Drugs.reservationsVersion + 1
        }
    }

    /** Тождество брони — пара «человек и упаковка». */
    private fun identityOf(reservation: Reservation): Op<Boolean> =
        (Reservations.userId eq reservation.userId) and (Reservations.drugId eq reservation.drugId)


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
