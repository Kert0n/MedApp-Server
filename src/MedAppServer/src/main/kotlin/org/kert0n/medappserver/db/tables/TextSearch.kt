// Постгресовый поиск по тексту, выраженный типами Exposed.
//
// Ни полнотекста, ни триграмм в переносимом DSL нет — но это не повод писать запрос строкой.
// Колонки, оператор и функция объявляются по разу здесь, а запрос из них собирается как из
// обычных выражений: с проверкой типов, со связыванием значений и без счёта вопросительных
// знаков. Пользуется всем этим `CatalogueStore`, и там же описано, как оно складывается в
// поиск целиком.

package org.kert0n.medappserver.db.tables

import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.stringParam

// ── Колонки, которые считает сама база ────────────────────────────────────────────────
//
// Обе — выражениями, а не колонками `Table`: они объявлены как `GENERATED ALWAYS AS ... STORED`,
// а такого Exposed в DDL не выражает. Попади они в `Table`, `SchemaUtils.create` создал бы на их
// месте обычные колонки, которые никто не заполняет.

/** Разобранный на лексемы документ записи — для поиска по точным словам. */
val searchDocument: Expression<String> = object : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("parsed_drugs.search_tsv")
    }
}

/**
 * Название, латинское написание, действующее вещество и производитель одной строкой.
 *
 * Отдельно от `search_tsv`, потому что триграммам нужен именно текст: в разобранном документе
 * опечатку не найти.
 */
val searchText: Expression<String> = object : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("parsed_drugs.search_text")
    }
}

// ── Как об этих колонках спрашивают ───────────────────────────────────────────────────

/**
 * Оператор `<%` из `pg_trgm`: у слова есть похожий участок внутри текста.
 *
 * Слово слева, текст справа — оператор несимметричен. Порог берётся из настройки
 * `pg_trgm.word_similarity_threshold`, а не из выражения: это плата за то, что оператор
 * индексируемый, в отличие от сравнения с вызовом функции.
 */
private class WordSimilarOp(
    left: Expression<*>,
    right: Expression<*>
) : ComparisonOp(left, right, "<%")

fun hasSimilarWord(word: String, text: Expression<*>): Op<Boolean> =
    WordSimilarOp(stringParam(word), text)

/**
 * `word_similarity(?, текст)` — насколько похож на слово лучший участок текста.
 *
 * Не `similarity`: та сравнивает строки целиком, и короткое слово против склейки четырёх полей
 * даёт почти ноль. Здесь ищется лучший участок, поэтому длина записи ответу не мешает — и
 * поэтому же лишнее слово в запросе перестаёт прятать нужную запись.
 */
fun wordSimilarity(word: String, text: Expression<*>): CustomFunction<Float?> =
    CustomFunction("word_similarity", FloatColumnType(), stringParam(word), text)

/** `@@` — документ отвечает запросу. */
private class FullTextMatchOp(
    left: Expression<*>,
    right: Expression<*>
) : ComparisonOp(left, right, "@@")

/**
 * Совпадение с полнотекстовым запросом: `search_tsv @@ plainto_tsquery('simple', ?)`.
 *
 * Словарь `simple` — тот же, которым колонка и посчитана: стеммингом мы не пользуемся, потому
 * что названия препаратов не слова языка.
 */
infix fun Expression<*>.matchesText(query: String): Op<Boolean> =
    FullTextMatchOp(
        this,
        CustomFunction("plainto_tsquery", TextColumnType(), stringLiteral("simple"), stringParam(query))
    )
