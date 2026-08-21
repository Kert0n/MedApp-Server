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

/**
 * Постгресовый поиск по тексту, выраженный типами Exposed.
 *
 * Ни полнотекста, ни триграмм в переносимом DSL нет — но это не повод писать запрос строкой.
 * Оператор и функция объявляются по разу здесь, а запрос из них собирается как из обычных
 * выражений: с проверкой типов, со связыванием значений и без счёта вопросительных знаков.
 */

/**
 * Оператор `<%` из `pg_trgm`: у слова есть похожий участок внутри текста.
 *
 * Слово слева, текст справа — оператор несимметричен. Порог берётся из настройки
 * `pg_trgm.word_similarity_threshold`, а не из выражения: «достаточно похоже» решает тот, кто
 * настраивает поиск. Зато оператор индексируемый, в отличие от сравнения с вызовом функции.
 */
private class WordSimilarOp(
    left: Expression<*>,
    right: Expression<*>
) : ComparisonOp(left, right, "<%")

fun hasSimilarWord(token: String, text: Expression<*>): Op<Boolean> =
    WordSimilarOp(stringParam(token), text)

/**
 * `word_similarity(?, текст)` — насколько похож на слово лучший участок текста.
 *
 * Не `similarity`: та сравнивает строки целиком, и короткое слово против склейки четырёх полей
 * даёт почти ноль. Здесь ищется лучший участок, поэтому длина записи ответу не мешает — и
 * поэтому же лишнее слово в запросе перестаёт прятать нужную запись.
 */
fun wordSimilarity(token: String, text: Expression<*>): CustomFunction<Float?> =
    CustomFunction("word_similarity", FloatColumnType(), stringParam(token), text)

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

/**
 * Колонка `search_tsv`, посчитанная самой базой.
 *
 * Выражением, а не колонкой таблицы: она объявлена как `GENERATED ALWAYS AS ... STORED`, и
 * такого Exposed в DDL не выражает. Попади она в `Table`, `SchemaUtils.create` создал бы на её
 * месте обычную колонку, которую никто не заполняет.
 */
val searchDocument: Expression<String> = object : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("parsed_drugs.search_tsv")
    }
}

/**
 * Колонка `search_text` — те же четыре поля одной строкой, посчитанные базой.
 *
 * Выражением по той же причине, что и `search_tsv`: `GENERATED ALWAYS AS` Exposed в DDL не
 * выражает, и попади колонка в `Table`, `SchemaUtils.create` создал бы на её месте обычную
 * пустую.
 */
val searchText: Expression<String> = object : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("parsed_drugs.search_text")
    }
}

