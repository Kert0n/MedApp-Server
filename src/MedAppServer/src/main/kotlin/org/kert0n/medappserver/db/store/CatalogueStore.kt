package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.kert0n.medappserver.db.tables.ilike
import org.kert0n.medappserver.db.tables.matchesText
import org.kert0n.medappserver.db.tables.searchDocument
import org.kert0n.medappserver.db.tables.trigramSimilar
import org.kert0n.medappserver.db.tables.trigramSimilarity
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
     * Нечёткий поиск: полнотекст, подстрока и триграммы, упорядоченные по точности совпадения.
     *
     * Постгресовые оператор и функция объявлены типами в `TextSearch.kt`, поэтому запрос
     * собирается обычными выражениями: значения связываются сами, а порядок аргументов
     * считать не приходится.
     */
    fun searchTemplates(term: String, likeTerm: String, limit: Int): List<DrugTemplate> {
        val anywhere = "%$likeTerm%"
        val fromStart = "$likeTerm%"

        return templateRows.selectAll()
            .where { matchesAnywhere(term, anywhere) }
            .orderBy(
                (searchDocument matchesText term) to SortOrder.DESC,
                matchRank(term, anywhere, fromStart) to SortOrder.ASC,
                bestSimilarity(term) to SortOrder.DESC,
                DrugTemplates.name to SortOrder.ASC
            )
            .limit(limit)
            .map { it.toDomain() }
    }

    /** Запись подходит, если её нашёл полнотекст, подстрока или триграммы. */
    private fun matchesAnywhere(term: String, anywhere: String): Op<Boolean> =
        (searchDocument matchesText term) or
            (DrugTemplates.name ilike anywhere) or
            (DrugTemplates.nameLat ilike anywhere) or
            (DrugTemplates.activeSubstance ilike anywhere) or
            (DrugTemplates.manufacturer ilike anywhere) or
            (DrugTemplates.name trigramSimilar term) or
            (DrugTemplates.nameLat trigramSimilar term) or
            (DrugTemplates.activeSubstance trigramSimilar term) or
            (DrugTemplates.manufacturer trigramSimilar term)

    /** Чем точнее совпало, тем меньше число: точное имя — ноль, чужое поле — шесть. */
    private fun matchRank(term: String, anywhere: String, fromStart: String): Expression<Int> =
        Case()
            .When(DrugTemplates.name.lowerCase() eq term.lowercase(), intLiteral(0))
            .When(DrugTemplates.name ilike fromStart, intLiteral(1))
            .When(DrugTemplates.name ilike anywhere, intLiteral(2))
            .When(DrugTemplates.nameLat ilike anywhere, intLiteral(3))
            .When(DrugTemplates.activeSubstance ilike anywhere, intLiteral(4))
            .When(DrugTemplates.manufacturer ilike anywhere, intLiteral(5))
            .Else(intLiteral(6))

    /** Из четырёх полей берётся то, что похоже больше всех. */
    private fun bestSimilarity(term: String): Expression<Float?> = CustomFunction(
        "GREATEST", FloatColumnType(),
        DrugTemplates.name.trigramSimilarity(term),
        Coalesce(DrugTemplates.nameLat, stringLiteral("")).trigramSimilarity(term),
        Coalesce(DrugTemplates.activeSubstance, stringLiteral("")).trigramSimilarity(term),
        Coalesce(DrugTemplates.manufacturer, stringLiteral("")).trigramSimilarity(term)
    )

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
