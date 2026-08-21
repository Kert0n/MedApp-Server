package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
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
     * Нечёткий поиск описан кусками SQL, а не выражениями Exposed.
     *
     * Здесь и полнотекст по документу, который считает сама база, и триграммное сходство, и
     * порядок «точное совпадение выше префикса, префикс выше подстроки». Ни одно из этого в
     * переносимых выражениях не выражается, а прятать `similarity()` за обёрткой значило бы
     * делать вид, что запрос переносим.
     *
     * Но кусок SQL — ещё не повод спускаться к драйверу. Запрос собран вокруг тех же колонок,
     * поэтому строки приходят разобранными: идентификатор остаётся `Uuid`, а не приезжает
     * джавовым из `ResultSet`. Заодно исчез второй заход в базу — раньше сырой запрос отдавал
     * идентификаторы, и по ним делалась ещё одна выборка ради самих записей.
     */
    fun searchTemplates(term: String, likeTerm: String, limit: Int): List<DrugTemplate> =
        templateRows.selectAll()
            .where { matchesAnywhere(term, likeTerm) }
            .orderBy(
                fullTextMatch(term) to SortOrder.DESC,
                matchRank(term, likeTerm) to SortOrder.ASC,
                bestSimilarity(term) to SortOrder.DESC,
                DrugTemplates.name to SortOrder.ASC
            )
            .limit(limit)
            .map { it.toDomain() }

    /** Запись подходит, если её нашёл полнотекст, подстрока или триграммы. */
    private fun matchesAnywhere(term: String, likeTerm: String): Op<Boolean> = SqlFragment(
        """
        (parsed_drugs.search_tsv @@ plainto_tsquery('simple', ?)
            OR parsed_drugs.name ILIKE ('%' || ? || '%')
            OR parsed_drugs.name_lat ILIKE ('%' || ? || '%')
            OR parsed_drugs.active_substance ILIKE ('%' || ? || '%')
            OR parsed_drugs.manufacturer ILIKE ('%' || ? || '%')
            OR parsed_drugs.name % ?
            OR parsed_drugs.name_lat % ?
            OR parsed_drugs.active_substance % ?
            OR parsed_drugs.manufacturer % ?)
        """.trimIndent(),
        listOf(term) + List(4) { likeTerm } + List(4) { term }
    )

    /** Найденное полнотекстом идёт выше найденного одними триграммами. */
    private fun fullTextMatch(term: String): Op<Boolean> =
        SqlFragment("(parsed_drugs.search_tsv @@ plainto_tsquery('simple', ?))", listOf(term))

    /** Чем точнее совпало, тем меньше число: точное имя — ноль, чужое поле — шесть. */
    private fun matchRank(term: String, likeTerm: String): Op<Int> = SqlFragment(
        """
        CASE
            WHEN lower(parsed_drugs.name) = lower(?) THEN 0
            WHEN parsed_drugs.name ILIKE (? || '%') THEN 1
            WHEN parsed_drugs.name ILIKE ('%' || ? || '%') THEN 2
            WHEN parsed_drugs.name_lat ILIKE ('%' || ? || '%') THEN 3
            WHEN parsed_drugs.active_substance ILIKE ('%' || ? || '%') THEN 4
            WHEN parsed_drugs.manufacturer ILIKE ('%' || ? || '%') THEN 5
            ELSE 6
        END
        """.trimIndent(),
        listOf(term) + List(5) { likeTerm }
    )

    /** Из четырёх полей берётся то, что похоже больше всех. */
    private fun bestSimilarity(term: String): Op<Double> = SqlFragment(
        """
        GREATEST(
            similarity(parsed_drugs.name, ?),
            similarity(coalesce(parsed_drugs.name_lat, ''), ?),
            similarity(coalesce(parsed_drugs.active_substance, ''), ?),
            similarity(coalesce(parsed_drugs.manufacturer, ''), ?)
        )
        """.trimIndent(),
        List(4) { term }
    )

    fun quantityUnits(): List<QuantityUnit> =
        QuantityUnits.selectAll().map { QuantityUnit(it[QuantityUnits.id], it[QuantityUnits.name]) }
            .sortedBy { it.name }

    fun formTypes(): List<FormType> =
        FormTypes.selectAll().map { FormType(it[FormTypes.id], it[FormTypes.name]) }.sortedBy { it.name }

    fun findQuantityUnit(id: Uuid): QuantityUnit? =
        QuantityUnits.selectAll().where { QuantityUnits.id eq id }
            .singleOrNull()?.let { QuantityUnit(it[QuantityUnits.id], it[QuantityUnits.name]) }

    fun findFormType(id: Uuid): FormType? =
        FormTypes.selectAll().where { FormTypes.id eq id }
            .singleOrNull()?.let { FormType(it[FormTypes.id], it[FormTypes.name]) }

    private val templateRows
        get() = DrugTemplates
            .join(FormTypes, JoinType.LEFT, DrugTemplates.formTypeId, FormTypes.id)
            .join(QuantityUnits, JoinType.LEFT, DrugTemplates.quantityUnitId, QuantityUnits.id)

    /**
     * Кусок SQL со связанными значениями.
     *
     * Текст остаётся текстом — его видно целиком, — но значения подставляются параметрами, а не
     * склейкой: имя препарата приходит от пользователя.
     */
    private class SqlFragment<T>(
        private val sql: String,
        private val arguments: List<String>
    ) : Op<T>() {

        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
            sql.split("?").forEachIndexed { index, chunk ->
                append(chunk)
                arguments.getOrNull(index)?.let { registerArgument(TextColumnType(), it) }
            }
        }
    }

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
