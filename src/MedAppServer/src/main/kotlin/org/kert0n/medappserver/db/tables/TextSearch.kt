package org.kert0n.medappserver.db.tables

import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
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
 * Оператор `%` из `pg_trgm`: похоже настолько, что порог `pg_trgm.similarity_threshold` пройден.
 *
 * Порог — настройка базы, а не запроса, и в этом смысл оператора: «достаточно похоже» решает
 * тот, кто настраивает поиск, а не тот, кто его пишет.
 */
class TrigramSimilarOp<T : String?>(
    left: Expression<T>,
    right: Expression<T>
) : ComparisonOp(left, right, "%")

infix fun <T : String?> ExpressionWithColumnType<T>.trigramSimilar(other: T): Op<Boolean> =
    TrigramSimilarOp(this, QueryParameter(other, columnType))

/** `similarity(x, ?)` из `pg_trgm` — насколько похоже, числом от нуля до единицы. */
fun <T : String?> Expression<T>.trigramSimilarity(query: String): CustomFunction<Float?> =
    CustomFunction("similarity", FloatColumnType(), this, stringParam(query))

/**
 * `ILIKE` — сравнение по образцу без учёта регистра.
 *
 * У Exposed есть только `LIKE`: регистронезависимость в стандарте не описана, и каждая СУБД
 * решает её по-своему.
 */
class ILikeOp<T : String?>(
    left: Expression<T>,
    right: Expression<T>
) : ComparisonOp(left, right, "ILIKE")

infix fun <T : String?> ExpressionWithColumnType<T>.ilike(pattern: T): Op<Boolean> =
    ILikeOp(this, QueryParameter(pattern, columnType))

/** `@@` — документ отвечает запросу. */
class FullTextMatchOp(
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
