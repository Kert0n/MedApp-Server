package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.stereotype.Component

/**
 * Приводит пример к типу поля, под которым он опубликован.
 *
 * Величины едут строкой, но springdoc выводит вид примера не из объявленного `type`, а из типа
 * свойства: у `BigDecimal` он разбирает `example = "100.0"` как число и печатает в YAML без
 * кавычек. Выходит схема, которая сама себе противоречит — `type: string` и пример-число
 * рядом, — и строгий генератор клиента на ней спотыкается.
 *
 * Чинится одним местом, а не кавычками в аннотациях: там их поставить негде, атрибут
 * `@Schema(example)` и так строка — значение теряется уже при разборе. И одним местом на весь
 * документ, а не на девять известных полей: правило «пример написан так же, как значение»
 * верно для любого поля, включая то, которого ещё нет.
 */
@Component
class StringExampleCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        openApi.components?.schemas?.values?.forEach { quoteExamples(it, HashSet()) }
    }

    /**
     * Схемы ссылаются друг на друга, и обход по значениям может прийти в ту же самую дважды.
     * Отсюда набор посещённых — по тождеству, а не по равенству: у пустых схем оно совпадает.
     */
    private fun quoteExamples(schema: Schema<*>?, seen: MutableSet<Schema<*>>) {
        if (schema == null || !seen.add(schema)) return

        val example = schema.example
        if (example != null && example !is String && declaresString(schema)) {
            schema.example = example.toString()
        }

        schema.properties?.values?.forEach { quoteExamples(it, seen) }
        quoteExamples(schema.items, seen)
        (schema.additionalProperties as? Schema<*>)?.let { quoteExamples(it, seen) }
    }

    /** В 3.1 тип живёт в `types`, но старое одиночное поле никуда не делось — смотрим оба. */
    private fun declaresString(schema: Schema<*>): Boolean =
        schema.type == STRING || schema.types?.contains(STRING) == true

    private companion object {
        const val STRING = "string"
    }
}
