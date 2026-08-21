package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.floatLiteral
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.times
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.kert0n.medappserver.db.tables.FormTypes
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.db.tables.hasSimilarWord
import org.kert0n.medappserver.db.tables.matchesText
import org.kert0n.medappserver.db.tables.searchDocument
import org.kert0n.medappserver.db.tables.searchText
import org.kert0n.medappserver.db.tables.wordSimilarity
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

    // ── Чтение по идентификатору ──────────────────────────────────────────────────────

    fun findTemplate(id: Uuid): DrugTemplate? =
        templateRows.selectAll().where { DrugTemplates.id eq id }.singleOrNull()?.toDomain()

    fun findQuantityUnit(id: Uuid): QuantityUnit? =
        QuantityUnits.selectAll().where { QuantityUnits.id eq id }.singleOrNull()?.toQuantityUnit()

    fun findFormType(id: Uuid): FormType? =
        FormTypes.selectAll().where { FormTypes.id eq id }.singleOrNull()?.toFormType()

    fun quantityUnits(): List<QuantityUnit> =
        QuantityUnits.selectAll().orderBy(QuantityUnits.name).map { it.toQuantityUnit() }

    fun formTypes(): List<FormType> =
        FormTypes.selectAll().orderBy(FormTypes.name).map { it.toFormType() }

    // ── Нечёткий поиск ────────────────────────────────────────────────────────────────
    //
    // Задача не «похожи ли две строки», а «сколько слов запроса объясняется этой записью».
    // Отсюда всё устройство ниже, и читать его лучше сверху вниз.
    //
    // На вход приходит уже разобранный запрос: слова (их выделил сервис — это политика
    // поиска) и он же целиком (нужен, чтобы отличить набранное точно от похожего).
    //
    //   1. Отбор.    Кандидат — запись, в которой нашлось хоть одно слово. Условие `OR`, а
    //                не `AND`: одно испорченное слово не должно стоить всей находки.
    //   2. Порядок.  Пять ступеней, от самого весомого к самому слабому:
    //                  • набрано точно — название запросу равно;
    //                  • найдено полнотекстом — слова совпали как слова, а не по буквам;
    //                  • сколько слов совпало — каждое найденное поднимает запись;
    //                  • сумма квадратов похожести — тонкая настройка внутри равного счёта;
    //                  • название — чтобы порядок был устойчив, а не случаен.
    //   3. Отсечка.  `LIMIT` и превращение строк в доменные карточки.
    //
    // Слово ищется по `search_text` — склейке названия, латинского написания, действующего
    // вещества и производителя. Сравнивать запрос целиком с отдельным полем нельзя: половина
    // запроса в поле не встречается никогда, сходство падает у всех, и чем длиннее запрос,
    // тем сильнее — вплоть до того, что нужная запись пропадает из выдачи совсем.

    fun searchTemplates(query: String, words: List<String>, limit: Int): List<DrugTemplate> {
        applyWordMatchThreshold()

        return templateRows.selectAll()
            .where { anyWordMatches(words) }
            .orderBy(
                exactName(query) to SortOrder.ASC,
                (searchDocument matchesText query) to SortOrder.DESC,
                matchedWords(words) to SortOrder.DESC,
                wordScore(words) to SortOrder.DESC,
                DrugTemplates.name to SortOrder.ASC
            )
            .limit(limit)
            .map { it.toDomain() }
    }

    /**
     * Порог, с которого `<%` считает слово найденным.
     *
     * Настройкой, а не выражением: у оператора он берётся только оттуда — такова плата за то,
     * что оператор индексируемый. `SET LOCAL` действует до конца транзакции и в соседние не
     * течёт. Умолчание `0.6` рассчитано на точный поиск и для опечаток слишком строгое.
     */
    private fun applyWordMatchThreshold() {
        TransactionManager.current()
            .exec("SET LOCAL pg_trgm.word_similarity_threshold = $WORD_MATCH_THRESHOLD")
    }

    /** Шаг 1: запись годится в кандидаты, если в ней нашлось хоть одно слово запроса. */
    private fun anyWordMatches(words: List<String>): Op<Boolean> =
        words.map { hasSimilarWord(it, searchText) }.reduce { left, right -> left or right }

    /**
     * Ступень 1: набранное точно идёт впереди похожего.
     *
     * Отдельно от счёта слов, потому что при запросе в одно слово все кандидаты набирают
     * поровну, и то, что человек написал название целиком, иначе ничем не проявится.
     *
     * Только точное равенство, без «начинается с»: такая ступень поднимала длинное название,
     * содержащее оба слова запроса, выше записи, у которой одно слово в названии, а другое в
     * производителе, — то есть выше правильного ответа.
     */
    private fun exactName(query: String): Expression<Int> =
        Case()
            .When(DrugTemplates.name.lowerCase() eq query.lowercase(), intLiteral(0))
            .Else(intLiteral(1))

    /** Ступень 3: сколько слов запроса нашлось — каждое найденное поднимает запись выше. */
    private fun matchedWords(words: List<String>): ExpressionWithColumnType<Int> {
        var found: ExpressionWithColumnType<Int> = intLiteral(0)
        words.forEach { word ->
            val counted = Case()
                .When(similarityOf(word) greaterEq WORD_MATCH_THRESHOLD, intLiteral(1))
                .Else(intLiteral(0))
            found = found plus counted
        }
        return found
    }

    /**
     * Ступень 4: сумма квадратов похожести — различает записи с равным числом совпавших слов.
     *
     * Квадрат, а не само значение: сильное совпадение ценнее двух слабых, а случайный
     * триграммный шум почти ничего не прибавляет.
     */
    private fun wordScore(words: List<String>): ExpressionWithColumnType<Float> {
        var score: ExpressionWithColumnType<Float> = floatLiteral(0f)
        words.forEach { word -> score = score plus (similarityOf(word) times similarityOf(word)) }
        return score
    }

    /** Похожесть без `null`: у не совпавшего слова она ноль, а не «неизвестно». */
    private fun similarityOf(word: String): ExpressionWithColumnType<Float> =
        Coalesce(wordSimilarity(word, searchText), floatLiteral(0f))

    // ── Строки в доменные типы ────────────────────────────────────────────────────────

    /**
     * Карточка справочника читается вместе со словарями.
     *
     * Соединение внешнее: и форма выпуска, и единица у записи необязательны, а выдача обязана
     * объяснять, почему запись нашлась, — значит приходит целиком, а не идентификаторами.
     */
    private val templateRows
        get() = DrugTemplates
            .join(FormTypes, JoinType.LEFT, DrugTemplates.formTypeId, FormTypes.id)
            .join(QuantityUnits, JoinType.LEFT, DrugTemplates.quantityUnitId, QuantityUnits.id)

    /**
     * Словарные значения собираются по идентификатору из самой записи, а не из строки словаря:
     * при внешнем соединении та половина строки пуста, и читать из неё нечего.
     */
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

    private fun ResultRow.toQuantityUnit(): QuantityUnit =
        QuantityUnit(this[QuantityUnits.id], this[QuantityUnits.name])

    private fun ResultRow.toFormType(): FormType =
        FormType(this[FormTypes.id], this[FormTypes.name])

    private companion object {
        /**
         * Порог, ниже которого слово считается не найденным.
         *
         * Одно число и на отбор кандидатов, и на счёт совпавших слов: два разных значения
         * означали бы, что запись попала в выдачу словом, которое при подсчёте не засчитано.
         */
        const val WORD_MATCH_THRESHOLD = 0.3f
    }
}
