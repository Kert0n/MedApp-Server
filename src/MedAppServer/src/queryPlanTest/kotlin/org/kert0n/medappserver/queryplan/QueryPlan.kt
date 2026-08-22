package org.kert0n.medappserver.queryplan

import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * План запроса — и утверждения о нём.
 *
 * Утверждается использование индекса, а не его наличие: «индекс объявлен» — пересказ схемы, и
 * такие тесты мы не заводим. Вопрос здесь один — доходит ли запрос до индекса на настоящем
 * объёме.
 */
class QueryPlan(private val nodes: List<JsonNode>, val sql: String) {

    /** Индекс назван в плане: запрос до него дошёл. */
    fun usesIndex(name: String) {
        assertTrue(
            nodes.any { it.path("Index Name").asString(null) == name },
            "запрос не дошёл до индекса $name:\n${describe()}\n\n$sql"
        )
    }

    /**
     * Полного прохода по этой таблице быть не должно.
     *
     * Именно по таблице, а не вообще: по крошечным словарям он законен и дешевле индекса.
     */
    fun scansNothingIn(table: String) {
        val scans = nodes.filter {
            it.path("Node Type").asString("") == "Seq Scan" && it.path("Relation Name").asString("") == table
        }
        assertTrue(scans.isEmpty(), "полный проход по $table:\n${describe()}\n\n$sql")
    }

    fun describe(): String =
        nodes.joinToString("\n") { node ->
            val type = node.path("Node Type").asString("?")
            val relation = node.path("Relation Name").asString("")
            val index = node.path("Index Name").asString("")
            "  $type ${relation.ifEmpty { "" }} ${index.ifEmpty { "" }}".trimEnd()
        }

    companion object {
        /** План выполнения запроса — со всеми вложенными узлами, разложенными в список. */
        fun of(jdbc: JdbcTemplate, sql: String): QueryPlan {
            val json = jdbc.queryForObject("EXPLAIN (FORMAT JSON) $sql", String::class.java)
                ?: error("EXPLAIN ничего не вернул")
            val root = ObjectMapper().readTree(json).first().path("Plan")
            return QueryPlan(flatten(root), sql)
        }

        /**
         * То же, но с выключенным полным проходом.
         *
         * Отвечает на другой вопрос: применим ли индекс к запросу вообще, даже если сегодня
         * планировщик его не выбрал. Настройка возвращается в обоих исходах, включая падение,
         * — иначе один упавший тест испортил бы все следующие.
         */
        fun withoutSeqScan(jdbc: JdbcTemplate, sql: String): QueryPlan {
            jdbc.execute("SET enable_seqscan = off")
            try {
                return of(jdbc, sql)
            } finally {
                jdbc.execute("SET enable_seqscan = on")
            }
        }

        private fun flatten(node: JsonNode): List<JsonNode> =
            listOf(node) + node.path("Plans").flatMap { flatten(it) }
    }
}
