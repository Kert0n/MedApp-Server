package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.kert0n.medappserver.db.tables.FormTypes
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.QuantityUnit
import org.springframework.stereotype.Component

/**
 * Хранилище справочника.
 *
 * Справочник ничей: он одинаков для всех, владельца у записи нет, и скоупить его нечем.
 */
@Component
class CatalogueStore {

    fun findTemplate(id: Uuid): DrugTemplate? =
        templateRows.selectAll().where { DrugTemplates.id eq id }.singleOrNull()?.toDomain()

    /**
     * Нечёткий поиск остаётся сырым SQL — и это честнее, чем прятать его за DSL.
     *
     * Здесь и полнотекст по документу, который считает сама база, и триграммное сходство, и
     * порядок выдачи «точное совпадение выше префикса, префикс выше подстроки». Ни одно из
     * этого в переносимых выражениях не выражается, а прятать `similarity()` за обёрткой
     * значило бы делать вид, что запрос переносим.
     */
    fun searchTemplates(term: String, likeTerm: String, limit: Int): List<DrugTemplate> {
        val found = mutableListOf<Uuid>()
        TransactionManager.current().exec(
            """
            SELECT id FROM parsed_drugs
            WHERE search_tsv @@ plainto_tsquery('simple', ?)
               OR name ILIKE ('%' || ? || '%')
               OR name_lat ILIKE ('%' || ? || '%')
               OR active_substance ILIKE ('%' || ? || '%')
               OR manufacturer ILIKE ('%' || ? || '%')
               OR name % ?
               OR name_lat % ?
               OR active_substance % ?
               OR manufacturer % ?
            ORDER BY
                (search_tsv @@ plainto_tsquery('simple', ?)) DESC,
                CASE
                    WHEN lower(name) = lower(?) THEN 0
                    WHEN name ILIKE (? || '%') THEN 1
                    WHEN name ILIKE ('%' || ? || '%') THEN 2
                    WHEN name_lat ILIKE ('%' || ? || '%') THEN 3
                    WHEN active_substance ILIKE ('%' || ? || '%') THEN 4
                    WHEN manufacturer ILIKE ('%' || ? || '%') THEN 5
                    ELSE 6
                END,
                GREATEST(
                    similarity(name, ?),
                    similarity(coalesce(name_lat, ''), ?),
                    similarity(coalesce(active_substance, ''), ?),
                    similarity(coalesce(manufacturer, ''), ?)
                ) DESC,
                name
            LIMIT ?
            """.trimIndent(),
            searchArguments(term, likeTerm, limit)
        // Сырой запрос читается драйвером, а он знает только джавовый тип: перевод стоит
        // здесь, на границе с JDBC, и дальше идентификатор всюду котлиновский.
        ) { rs -> while (rs.next()) found += rs.getObject(1, java.util.UUID::class.java).toKotlinUuid() }

        if (found.isEmpty()) return emptyList()
        // Порядок задаёт запрос выше, поэтому выборка по идентификаторам пересортировывается им.
        val byId = templateRows.selectAll().where { DrugTemplates.id inList found }
            .associate { it[DrugTemplates.id] to it.toDomain() }
        return found.mapNotNull { byId[it] }
    }

    fun quantityUnits(): List<QuantityUnit> =
        QuantityUnits.selectAll().orderBy(QuantityUnits.name).map { it.toQuantityUnit() }

    fun formTypes(): List<FormType> =
        FormTypes.selectAll().orderBy(FormTypes.name).map { it.toFormType() }

    fun findQuantityUnit(id: Uuid): QuantityUnit? =
        QuantityUnits.selectAll().where { QuantityUnits.id eq id }.singleOrNull()?.toQuantityUnit()

    fun findFormType(id: Uuid): FormType? =
        FormTypes.selectAll().where { FormTypes.id eq id }.singleOrNull()?.toFormType()

    private val templateRows
        get() = DrugTemplates
            .join(FormTypes, JoinType.LEFT, DrugTemplates.formTypeId, FormTypes.id)
            .join(QuantityUnits, JoinType.LEFT, DrugTemplates.quantityUnitId, QuantityUnits.id)

    /**
     * Значения по местам подстановки — в том же порядке, в каком они стоят в запросе.
     *
     * Список длинный не от сложности, а оттого, что одно и то же слово ищется по четырём полям
     * и дважды: сперва чтобы найти, потом чтобы упорядочить. Поэтому он и разбит по строчкам
     * запроса, а не свёрнут в одно выражение: сбитый порядок здесь ничем не проявится, кроме
     * неверной выдачи.
     */
    private fun searchArguments(term: String, likeTerm: String, limit: Int): List<Pair<IColumnType<*>, Any?>> =
        buildList {
            fun text(value: String) = add(TextColumnType() to value)

            text(term)                       // WHERE: полнотекст по документу
            repeat(4) { text(likeTerm) }     // WHERE: подстрока по четырём полям
            repeat(4) { text(term) }         // WHERE: триграммы по тем же полям

            text(term)                       // ORDER BY: найденное полнотекстом выше
            text(term)                       // ORDER BY: точное совпадение имени
            repeat(5) { text(likeTerm) }     // ORDER BY: лестница префикса и подстроки
            repeat(4) { text(term) }         // ORDER BY: сходство по четырём полям

            add(IntegerColumnType() to limit)
        }

    private fun ResultRow.toQuantityUnit(): QuantityUnit =
        QuantityUnit(this[QuantityUnits.id], this[QuantityUnits.name])

    private fun ResultRow.toFormType(): FormType =
        FormType(this[FormTypes.id], this[FormTypes.name])

    private fun ResultRow.toDomain(): DrugTemplate = DrugTemplate(
        id = this[DrugTemplates.id],
        name = this[DrugTemplates.name],
        nameLat = this[DrugTemplates.nameLat],
        activeSubstance = this[DrugTemplates.activeSubstance],
        formType = this[DrugTemplates.formTypeId]?.let { FormType(it, this[FormTypes.name]) },
        category = this[DrugTemplates.category],
        quantityUnit = this[DrugTemplates.quantityUnitId]?.let {
            QuantityUnit(it, this[QuantityUnits.name])
        },
        manufacturer = this[DrugTemplates.manufacturer],
        country = this[DrugTemplates.country],
        description = this[DrugTemplates.description]
    )
}
