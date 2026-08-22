package org.kert0n.medappserver.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Чтение чужого JSON — тем же, чем говорит приложение.
 *
 * Разбираются не свои DTO, а чужие документы: опубликованный контракт и вывод `EXPLAIN`. Схемы у
 * них нет, поэтому дерево обходится как дерево, без типов.
 *
 * Jackson в проекте есть, но занят одним делом: `ProblemDetail` — класс Spring без
 * `@Serializable`, и по правилу Boot 4 достаётся ему. Всё остальное — на kotlinx, включая
 * оснастку: два способа читать JSON в одном репозитории означали бы, что выбор между ними каждый
 * раз делается заново.
 *
 * Отсутствующее поле не роняет обход, а даёт `null` — как `path` у деревьев Jackson.
 */
fun String.asJsonTree(): JsonElement = Json.parseToJsonElement(this)

/** Поле объекта; `null`, если поля нет или это не объект. */
fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

/** Поля объекта парами, в порядке документа. */
fun JsonElement?.fields(): Map<String, JsonElement> = (this as? JsonObject).orEmpty()

/** Элементы массива; пусто, если это не массив. */
fun JsonElement?.items(): List<JsonElement> = (this as? JsonArray).orEmpty()

/** Текст примитива; `null`, если это не примитив. */
fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Есть ли такое поле — для проверок «объявлено или нет». */
fun JsonElement?.has(name: String): Boolean = field(name) != null
