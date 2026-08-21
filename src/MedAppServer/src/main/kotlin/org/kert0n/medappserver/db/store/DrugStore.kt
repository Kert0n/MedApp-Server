package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.kert0n.medappserver.db.tables.Drugs
import org.kert0n.medappserver.db.tables.FormTypes
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата упаковки.
 *
 * Наружу — только доменные типы. Броней здесь нет: упаковка ими не владеет.
 *
 * Каждое чтение соединяется со словарями, потому что доменное количество несёт имя единицы.
 * Соединение написано один раз, в `rows`, и видно глазами — в отличие от `EAGER`, о котором
 * приходилось помнить.
 */
@Component
class DrugStore {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun find(drugId: Uuid, userId: Uuid): Drug? =
        drugsWhere { (Drugs.id eq drugId) and accessibleTo(userId) }.singleOrNull()?.toDomain()

    fun findAllInMedKit(medKitId: Uuid, userId: Uuid): List<Drug> =
        drugsWhere { (Drugs.medKitId eq medKitId) and accessibleTo(userId) }
            .orderBy(Drugs.name)
            .map { it.toDomain() }

    fun findAllOfUser(userId: Uuid): List<Drug> =
        drugsWhere { accessibleTo(userId) }.orderBy(Drugs.name).map { it.toDomain() }

    // ── Команды ──────────────────────────────────────────────────────────────────

    fun insert(drug: Drug) {
        Drugs.insert { it.write(drug) }
    }

    fun save(drug: Drug) {
        Drugs.update({ Drugs.id eq drug.id }) { it.write(drug) }
    }

    /**
     * Уничтожение пачки — только пачки.
     *
     * Брони снимает `DrugDisposal`: их исчезновение вслед за упаковкой — правило, а не
     * подробность записи, и в запросе ему не место.
     */
    fun delete(drug: Drug) {
        Drugs.deleteWhere { Drugs.id eq drug.id }
    }

    /** Все упаковки аптечки — в другую, одним запросом. Брони убирает вызывающий. */
    fun moveAllToMedKit(sourceMedKitId: Uuid, targetMedKitId: Uuid) {
        Drugs.update({ Drugs.medKitId eq sourceMedKitId }) { it[medKitId] = targetMedKitId }
    }

    /**
     * Доступ проверяет сам запрос: чужой упаковки для вызывающего не существует.
     *
     * Предикат вынесен, чтобы его нельзя было забыть в новом чтении, — единственное место,
     * где он написан.
     */
    private fun accessibleTo(userId: Uuid): Op<Boolean> =
        Drugs.medKitId inSubQuery MedKitMemberships
            .select(MedKitMemberships.medKitId)
            .where { MedKitMemberships.userId eq userId }

    /** Упаковки со словарями, отобранные условием. */
    private fun drugsWhere(condition: () -> Op<Boolean>): Query =
        withVocabulary.selectAll().where(condition())

    private val withVocabulary: Join
        get() = Drugs
            .join(QuantityUnits, JoinType.INNER, Drugs.quantityUnitId, QuantityUnits.id)
            .join(FormTypes, JoinType.LEFT, Drugs.formTypeId, FormTypes.id)

    private fun ResultRow.toDomain(): Drug = Drug(
        id = this[Drugs.id],
        medKitId = this[Drugs.medKitId],
        name = this[Drugs.name],
        quantity = Quantity(this[Drugs.quantity], QuantityUnit(this[QuantityUnits.id], this[QuantityUnits.name])),
        formType = this[Drugs.formTypeId]?.let { FormType(it, this[FormTypes.name]) },
        category = this[Drugs.category],
        manufacturer = this[Drugs.manufacturer],
        country = this[Drugs.country],
        description = this[Drugs.description],
        version = this[Drugs.version]
    )
}

/** Перенос состояния в строку. Каждое поле названо один раз — забытое видно глазами. */
private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(drug: Drug) {
    this[Drugs.id] = drug.id
    this[Drugs.name] = drug.name
    this[Drugs.quantity] = drug.quantity.amount
    this[Drugs.quantityUnitId] = drug.quantity.unit.id
    this[Drugs.formTypeId] = drug.formType?.id
    this[Drugs.category] = drug.category
    this[Drugs.manufacturer] = drug.manufacturer
    this[Drugs.country] = drug.country
    this[Drugs.description] = drug.description
    this[Drugs.medKitId] = drug.medKitId
    this[Drugs.version] = drug.version
}
