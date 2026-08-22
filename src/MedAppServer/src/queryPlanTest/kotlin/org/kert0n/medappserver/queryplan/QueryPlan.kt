package org.kert0n.medappserver.queryplan

import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import kotlinx.serialization.json.JsonElement
import org.kert0n.medappserver.testutil.asJsonTree
import org.kert0n.medappserver.testutil.field
import org.kert0n.medappserver.testutil.items
import org.kert0n.medappserver.testutil.text

/**
 * План запроса — и утверждения о нём.
 *
 * Утверждается использование индекса, а не его наличие: «индекс объявлен» — пересказ схемы, и
 * такие тесты мы не заводим. Вопрос здесь один — доходит ли запрос до индекса на настоящем
 * объёме.
 */
class QueryPlan(private val nodes: List<JsonElement>, val sql: String) {

    /** Индекс назван в плане: запрос до него дошёл. */
    fun usesIndex(name: String) {
        assertTrue(
            nodes.any { it.field("Index Name").text() == name },
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
            it.field("Node Type").text() == "Seq Scan" && it.field("Relation Name").text() == table
        }
        assertTrue(scans.isEmpty(), "полный проход по $table:\n${describe()}\n\n$sql")
    }

    fun describe(): String =
        nodes.joinToString("\n") { node ->
            val type = node.field("Node Type").text() ?: "?"
            val relation = node.field("Relation Name").text().orEmpty()
            val index = node.field("Index Name").text().orEmpty()
            "  $type ${relation.ifEmpty { "" }} ${index.ifEmpty { "" }}".trimEnd()
        }

    companion object {
        /**
         * Отчёт складывается в `build/reports/queryPlans`, а не в репозиторий.
         *
         * План зависит от версии Postgres и собранной статистики: снимок его в git шумел бы
         * при каждом обновлении и ничего бы не доказывал. В CI это артефакт — посмотреть,
         * когда измерение упало.
         */
        private val report = java.io.File("build/reports/queryPlans/plans.txt").apply {
            parentFile.mkdirs()
            writeText("")
        }

        /** План выполнения запроса — со всеми вложенными узлами, разложенными в список. */
        fun of(jdbc: JdbcTemplate, sql: String): QueryPlan {
            val json = jdbc.queryForObject("EXPLAIN (FORMAT JSON) $sql", String::class.java)
                ?: error("EXPLAIN ничего не вернул")
            val root = json.asJsonTree().items().first().field("Plan")
            val plan = QueryPlan(flatten(root), sql)
            report.appendText("── $sql\n${plan.describe()}\n\n")
            return plan
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

        private fun flatten(node: JsonElement?): List<JsonElement> =
            listOfNotNull(node) + node.field("Plans").items().flatMap { flatten(it) }
    }
}
