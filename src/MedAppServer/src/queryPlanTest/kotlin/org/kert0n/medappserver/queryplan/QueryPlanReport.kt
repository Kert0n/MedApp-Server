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
    val plans: List<QueryPlan> = emptyList(),
    val complexity: String = "Θ(1)"
) {
    val shape: QueryShape? get() = statements.takeIf { it.isNotEmpty() }?.queryShape()
    val indexes: Set<String> get() = plans.flatMapTo(sortedSetOf()) { it.indexes }
    val sequentialScans: Set<String>
        get() = plans.flatMapTo(sortedSetOf()) { it.sequentiallyScanned }
}

/**
 * Единый источник таблицы результатов. Отчёт строится только из записанных JDBC-вызовов
 * и разобранных EXPLAIN, поэтому его строки нельзя рассинхронизировать с тестом вручную.
 */
class QueryPlanReport(private val objectMapper: ObjectMapper) {

    private val measurements = mutableListOf<QueryMeasurement>()

    fun record(measurement: QueryMeasurement) {
        measurements += measurement
    }

    fun write(outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        val sorted = measurements.sortedWith(
            compareBy(QueryMeasurement::owner, QueryMeasurement::method, QueryMeasurement::branch)
                .thenBy { it.size ?: -1 }
        )
        Files.writeString(outputDirectory.resolve("database-query-report.md"), markdown(sorted))
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(
                outputDirectory.resolve("database-query-report.json").toFile(),
                sorted.map(::serializable)
            )
    }

    private fun serializable(row: QueryMeasurement): Map<String, Any?> = linkedMapOf(
        "owner" to row.owner,
        "method" to row.method,
        "branch" to row.branch,
        "size" to row.size,
        "result" to row.result,
        "complexity" to row.complexity,
        "counts" to SqlKind.entries.associate { it.name to (row.shape?.count(it) ?: 0) },
        "statements" to row.statements.map {
            mapOf(
                "kind" to it.kind.name,
                "fingerprint" to it.fingerprint,
                "parameters" to it.parameters.map { value -> value?.toString() }
            )
        },
        "plans" to row.plans.map {
            mapOf(
                "nodes" to it.nodeTypes,
                "indexes" to it.indexes,
                "sequentialScans" to it.sequentiallyScanned
            )
        }
    )

    private fun markdown(rows: List<QueryMeasurement>): String = buildString {
        appendLine("# Database query report")
        appendLine()
        appendLine("Сформирован автоматически из JDBC recording и `EXPLAIN (FORMAT JSON)`.")
        appendLine()
        appendLine("| Сервис.метод | Ветка | Размер | SELECT | INSERT | UPDATE | DELETE | Асимптотика SQL | Индексы | Результат |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---|---|---|")
        rows.forEach { row ->
            val shape = row.shape
            val scanNote = row.sequentialScans.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Seq Scan: ")
            val indexCell = (row.indexes.joinToString().ifBlank { "—" } +
                (scanNote?.let { "; $it" } ?: "")).escape()
            appendLine(
                "| `${row.owner}.${row.method}` | ${row.branch.escape()} | ${row.size ?: "—"} | " +
                    "${shape?.count(SqlKind.SELECT) ?: 0} | ${shape?.count(SqlKind.INSERT) ?: 0} | " +
                    "${shape?.count(SqlKind.UPDATE) ?: 0} | ${shape?.count(SqlKind.DELETE) ?: 0} | " +
                    "${row.complexity} | $indexCell | ${row.result.escape()} |"
            )
        }
        appendLine()
        appendLine("## Test tasks")
        appendLine()
        appendLine("| Набор | Всего | Успешно | Пропущено | Ошибки | Время, с |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        testSuites().forEach { suite ->
            appendLine(
                "| ${suite.name} | ${suite.total} | ${suite.total - suite.skipped - suite.failures - suite.errors} | " +
                    "${suite.skipped} | ${suite.failures + suite.errors} | ${"%.3f".format(suite.time)} |"
            )
        }
    }

    private fun testSuites(): List<SuiteResult> {
        val project = Path.of(System.getProperty("user.dir"))
        return listOf("test", "queryPlanTest").mapNotNull { task ->
            val directory = project.resolve("build/test-results/$task")
            if (!Files.isDirectory(directory)) return@mapNotNull null
            val xmlFiles = Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith("TEST-") && it.toString().endsWith(".xml") }
                    .toList()
            }
            if (xmlFiles.isEmpty()) return@mapNotNull null
            val factory = DocumentBuilderFactory.newInstance()
            val suites = xmlFiles.map { factory.newDocumentBuilder().parse(it.toFile()).documentElement }
            SuiteResult(
                name = task,
                total = suites.sumOf { it.getAttribute("tests").toInt() },
                skipped = suites.sumOf { it.getAttribute("skipped").ifBlank { "0" }.toInt() },
                failures = suites.sumOf { it.getAttribute("failures").ifBlank { "0" }.toInt() },
                errors = suites.sumOf { it.getAttribute("errors").ifBlank { "0" }.toInt() },
                time = suites.sumOf { it.getAttribute("time").ifBlank { "0" }.toDouble() }
            )
        }
    }

    private fun String.escape(): String = replace("|", "\\|").replace("\n", " ")
}

private data class SuiteResult(
    val name: String,
    val total: Int,
    val skipped: Int,
    val failures: Int,
    val errors: Int,
    val time: Double
)
