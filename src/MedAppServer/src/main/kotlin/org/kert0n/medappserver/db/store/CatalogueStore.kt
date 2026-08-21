package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.floatLiteral
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.times
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.kert0n.medappserver.db.tables.matchesText
import org.kert0n.medappserver.db.tables.searchDocument
import org.kert0n.medappserver.db.tables.hasSimilarWord
import org.kert0n.medappserver.db.tables.searchText
import org.kert0n.medappserver.db.tables.wordSimilarity
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
     * Нечёткий поиск по словам запроса.
     *
     * Слово запроса ищется где угодно в записи — в названии, латинском написании, действующем
     * веществе или производителе, склеенных в `search_text`. Сравнивать весь запрос целиком с
     * отдельным полем нельзя: половина запроса в поле не встречается никогда, сходство падает у
     * всех, и чем длиннее запрос, тем сильнее — вплоть до того, что нужная запись просто
     * пропадает из выдачи.
     *
     * Условие по словам — `OR`, а не `AND`: одно испорченное слово не должно стоить человеку
     * всей находки. Сколько слов совпало, учитывается порядком, а не отбором.
     */
    fun searchTemplates(query: String, tokens: List<String>, limit: Int): List<DrugTemplate> {
        // Порог `<%` живёт в настройке, выражением его не задать. SET LOCAL действует до конца
        // транзакции и дальше не течёт. Умолчание 0.6 для опечаток слишком строгое.
        TransactionManager.current().exec("SET LOCAL pg_trgm.word_similarity_threshold = $WORD_MATCH_THRESHOLD")

        return templateRows.selectAll()
            .where { anyWordMatches(tokens) }
            .orderBy(
                exactName(query) to SortOrder.ASC,
                (searchDocument matchesText query) to SortOrder.DESC,
                matchedWords(tokens) to SortOrder.DESC,
                wordScore(tokens) to SortOrder.DESC,
                DrugTemplates.name to SortOrder.ASC
            )
            .limit(limit)
            .map { it.toDomain() }
    }

    /** Запись годится в кандидаты, если в ней нашлось хоть одно слово запроса. */
    private fun anyWordMatches(tokens: List<String>): Op<Boolean> =
        tokens.map { hasSimilarWord(it, searchText) }.reduce { left, right -> left or right }

    /**
     * Набранное точно идёт впереди похожего.
     *
     * Отдельной ступенью, а не через счёт слов: при запросе в одно слово все кандидаты набирают
     * поровну, и то, что человек написал название целиком, иначе ничем не проявится.
     *
     * Только точное равенство, без «начинается с»: на многословном запросе такая ступень
     * поднимала длинное название, содержащее оба слова, выше записи, у которой одно слово в
     * названии, а другое в производителе — то есть выше правильного ответа.
     */
    private fun exactName(query: String): Expression<Int> =
        Case()
            .When(DrugTemplates.name.lowerCase() eq query.lowercase(), intLiteral(0))
            .Else(intLiteral(1))

    /** Сколько слов запроса нашлось: каждое найденное поднимает запись выше. */
    private fun matchedWords(tokens: List<String>): ExpressionWithColumnType<Int> {
        var found: ExpressionWithColumnType<Int> = intLiteral(0)
        tokens.forEach { token ->
            val counted = Case()
                .When(similarityOf(token) greaterEq WORD_MATCH_THRESHOLD, intLiteral(1))
                .Else(intLiteral(0))
            found = found plus counted
        }
        return found
    }

    /**
     * Сумма квадратов похожести — тонкая настройка внутри одинакового числа совпавших слов.
     *
     * Квадрат, а не само значение: сильное совпадение ценнее двух слабых, а случайный
     * триграммный шум почти ничего не прибавляет.
     */
    private fun wordScore(tokens: List<String>): ExpressionWithColumnType<Float> {
        var score: ExpressionWithColumnType<Float> = floatLiteral(0f)
        tokens.forEach { token -> score = score plus (similarityOf(token) times similarityOf(token)) }
        return score
    }

    /** Похожесть без `null`: у не совпавшего слова она ноль, а не «неизвестно». */
    private fun similarityOf(token: String): ExpressionWithColumnType<Float> =
        Coalesce(wordSimilarity(token, searchText), floatLiteral(0f))

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

    private companion object {
        /**
         * Порог, ниже которого слово считается не найденным.
         *
         * Одно число на отбор кандидатов и на счёт совпавших слов: два разных значения означали
         * бы, что запись попала в выдачу словом, которое при подсчёте не засчитано.
         */
        const val WORD_MATCH_THRESHOLD = 0.3f
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
