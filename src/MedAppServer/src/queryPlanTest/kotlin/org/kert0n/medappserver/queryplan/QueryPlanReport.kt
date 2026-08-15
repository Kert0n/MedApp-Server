package org.kert0n.medappserver.queryplan

import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class QueryMeasurement(
    val owner: String,
    val method: String,
    val branch: String,
    val size: Int?,
    val result: String,
    val statements: List<ExecutedStatement>,
    val naturalPlans: List<QueryPlan> = emptyList(),
    val forcedPlans: List<QueryPlan> = emptyList(),
    val complexity: String = "Θ(1)"
)

class QueryPlanReport(private val objectMapper: ObjectMapper) {
    private val measurements = mutableListOf<QueryMeasurement>()

    fun record(measurement: QueryMeasurement) { measurements += measurement }

    fun write(outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        val rows = measurements.sortedWith(
            compareBy(QueryMeasurement::owner, QueryMeasurement::method, QueryMeasurement::branch)
                .thenBy { it.size ?: -1 }
        )
        Files.writeString(outputDirectory.resolve("database-query-report.md"), markdown(rows))
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            outputDirectory.resolve("database-query-report.json").toFile(),
            mapOf("measurements" to rows.map(::serializable), "testTasks" to testTasks())
        )
    }

    private fun serializable(row: QueryMeasurement): Map<String, Any?> = linkedMapOf(
        "owner" to row.owner,
        "method" to row.method,
        "branch" to row.branch,
        "size" to row.size,
        "result" to row.result,
        "complexity" to row.complexity,
        "counts" to SqlKind.entries.associate { kind -> kind.name to row.statements.count { it.kind == kind } },
        "statements" to row.statements.map {
            mapOf("kind" to it.kind.name, "fingerprint" to it.fingerprint,
                "parameters" to it.parameters.map { value -> value?.toString() })
        },
        "naturalIndexes" to row.naturalPlans.flatMap(QueryPlan::indexes).distinct().sorted(),
        "forcedIndexes" to row.forcedPlans.flatMap(QueryPlan::indexes).distinct().sorted(),
        "naturalSequentialScans" to row.naturalPlans.flatMap(QueryPlan::sequentiallyScanned).distinct().sorted(),
        "forcedSequentialScans" to row.forcedPlans.flatMap(QueryPlan::sequentiallyScanned).distinct().sorted()
    )

    private fun markdown(rows: List<QueryMeasurement>) = buildString {
        appendLine("# Database query report")
        appendLine()
        appendLine("Generated from recorded JDBC executions and EXPLAIN (FORMAT JSON).")
        appendLine()
        appendLine("| Сервис.метод | Ветка | Размер | SELECT | INSERT | UPDATE | DELETE | Асимптотика SQL | Индексы | Результат |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---|---|---|")
        rows.forEach { row ->
            val indexes = (row.naturalPlans + row.forcedPlans).flatMap(QueryPlan::indexes).distinct().sorted()
            appendLine("| `${row.owner}.${row.method}` | ${row.branch.escape()} | ${row.size ?: "—"} | " +
                "${row.statements.count { it.kind == SqlKind.SELECT }} | " +
                "${row.statements.count { it.kind == SqlKind.INSERT }} | " +
                "${row.statements.count { it.kind == SqlKind.UPDATE }} | " +
                "${row.statements.count { it.kind == SqlKind.DELETE }} | ${row.complexity} | " +
                "${indexes.joinToString().ifBlank { "—" }.escape()} | ${row.result.escape()} |")
        }
        appendLine()
        appendLine("## Test tasks")
        appendLine()
        appendLine("| Набор | Команда | Всего | Успешно | Пропущено | Ошибки | Время, с |")
        appendLine("|---|---|---:|---:|---:|---:|---:|")
        testTasks().forEach { task ->
            appendLine("| ${task["name"]} | `./gradlew ${task["name"]} --no-daemon` | ${task["total"]} | " +
                "${task["successful"]} | ${task["skipped"]} | ${task["errors"]} | ${task["seconds"]} |")
        }
    }

    private fun testTasks(): List<Map<String, Any>> {
        val project = Path.of(System.getProperty("user.dir"))
        return listOf("test", "queryPlanTest").mapNotNull { task ->
            val directory = project.resolve("build/test-results/$task")
            if (!Files.isDirectory(directory)) return@mapNotNull null
            val files = Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().startsWith("TEST-") && it.toString().endsWith(".xml") }.toList()
            }
            if (files.isEmpty()) return@mapNotNull null
            val factory = DocumentBuilderFactory.newInstance()
            val suites = files.map { factory.newDocumentBuilder().parse(it.toFile()).documentElement }
            val total = suites.sumOf { it.getAttribute("tests").toInt() }
            val skipped = suites.sumOf { it.getAttribute("skipped").ifBlank { "0" }.toInt() }
            val failures = suites.sumOf { it.getAttribute("failures").ifBlank { "0" }.toInt() }
            val errors = suites.sumOf { it.getAttribute("errors").ifBlank { "0" }.toInt() }
            linkedMapOf(
                "name" to task,
                "command" to "./gradlew $task --no-daemon",
                "total" to total,
                "successful" to total - skipped - failures - errors,
                "skipped" to skipped,
                "errors" to failures + errors,
                "seconds" to suites.sumOf { it.getAttribute("time").ifBlank { "0" }.toDouble() }
            )
        }
    }

    private fun String.escape() = replace("|", "\\|").replace("\n", " ")
}
