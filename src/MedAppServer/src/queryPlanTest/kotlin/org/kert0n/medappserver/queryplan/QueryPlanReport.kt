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
) {
    val shape: QueryShape? get() = statements.takeIf { it.isNotEmpty() }?.queryShape()
    val naturalIndexes: Set<String>
        get() = naturalPlans.flatMapTo(sortedSetOf()) { it.indexes }
    val forcedIndexes: Set<String>
        get() = forcedPlans.flatMapTo(sortedSetOf()) { it.indexes }
    val naturalSequentialScans: Set<String>
        get() = naturalPlans.flatMapTo(sortedSetOf()) { it.sequentiallyScanned }
    val forcedSequentialScans: Set<String>
        get() = forcedPlans.flatMapTo(sortedSetOf()) { it.sequentiallyScanned }
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
        val groups = sorted.groupBy { MeasurementKey(it.owner, it.method, it.branch) }
            .map { (key, rows) -> MeasurementGroup(key, rows, inferComplexity(rows)) }
        Files.writeString(outputDirectory.resolve("database-query-report.md"), markdown(groups))
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            outputDirectory.resolve("database-query-report.json").toFile(),
            mapOf(
                "measurements" to groups.map(::serializable),
                "testTasks" to testSuites().map(::serializable)
            )
        )
    }

    private fun serializable(group: MeasurementGroup): Map<String, Any?> = linkedMapOf(
        "owner" to group.key.owner,
        "method" to group.key.method,
        "branch" to group.key.branch,
        "complexity" to group.complexity,
        "points" to group.rows.map { row ->
            linkedMapOf(
                "size" to row.size,
                "result" to row.result,
                "counts" to SqlKind.entries.associate { it.name to (row.shape?.count(it) ?: 0) },
                "statements" to row.statements.map {
                    mapOf(
                        "kind" to it.kind.name,
                        "fingerprint" to it.fingerprint,
                        "parameters" to it.parameters.map { value -> value?.toString() }
                    )
                },
                "naturalPlans" to row.naturalPlans.map {
                    mapOf(
                        "nodes" to it.nodeTypes,
                        "indexes" to it.indexes,
                        "sequentialScans" to it.sequentiallyScanned
                    )
                },
                "forcedPlans" to row.forcedPlans.map {
                    mapOf(
                        "nodes" to it.nodeTypes,
                        "indexes" to it.indexes,
                        "sequentialScans" to it.sequentiallyScanned
                    )
                }
            )
        }
    )

    private fun serializable(row: SuiteResult): Map<String, Any> = linkedMapOf(
        "name" to row.name,
        "command" to "./gradlew ${row.name} --no-daemon",
        "total" to row.total,
        "successful" to row.total - row.skipped - row.failures - row.errors,
        "skipped" to row.skipped,
        "errors" to row.failures + row.errors,
        "seconds" to row.time
    )

    private fun markdown(groups: List<MeasurementGroup>): String = buildString {
        appendLine("# Database query report")
        appendLine()
        appendLine("Сформирован автоматически из JDBC recording и `EXPLAIN (FORMAT JSON)`.")
        appendLine()
        appendLine("| Сервис.метод | Ветка | Размеры | SELECT | INSERT | UPDATE | DELETE | Асимптотика SQL | Natural / forced индексы | Результат |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---|---|---|")
        groups.forEach { group ->
            val rows = group.rows
            val naturalIndexes = rows.flatMapTo(sortedSetOf()) { it.naturalIndexes }
            val forcedIndexes = rows.flatMapTo(sortedSetOf()) { it.forcedIndexes }
            val naturalScan = rows.flatMapTo(sortedSetOf()) { it.naturalSequentialScans }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " Seq: ")
            val forcedScan = rows.flatMapTo(sortedSetOf()) { it.forcedSequentialScans }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " Seq: ")
            val indexCell = (
                "natural=${naturalIndexes.joinToString().ifBlank { "—" }}${naturalScan.orEmpty()}; " +
                    "forced=${forcedIndexes.joinToString().ifBlank { "—" }}${forcedScan.orEmpty()}"
                ).escape()
            appendLine(
                "| `${group.key.owner}.${group.key.method}` | ${group.key.branch.escape()} | " +
                    "${rows.joinToString { it.size?.toString() ?: "—" }} | " +
                    "${counts(rows, SqlKind.SELECT)} | ${counts(rows, SqlKind.INSERT)} | " +
                    "${counts(rows, SqlKind.UPDATE)} | ${counts(rows, SqlKind.DELETE)} | " +
                    "${group.complexity} | $indexCell | " +
                    "${rows.map { it.result }.distinct().joinToString("; ").escape()} |"
            )
        }
        appendLine()
        appendLine("## Test tasks")
        appendLine()
        appendLine("| Набор | Команда | Всего | Успешно | Пропущено | Ошибки | Время, с | Отчёт |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---|")
        testSuites().forEach { suite ->
            appendLine(
                "| ${suite.name} | `./gradlew ${suite.name} --no-daemon` | ${suite.total} | " +
                    "${suite.total - suite.skipped - suite.failures - suite.errors} | " +
                    "${suite.skipped} | ${suite.failures + suite.errors} | " +
                    "${"%.3f".format(suite.time)} | `build/reports/tests/${suite.name}` |"
            )
        }
    }

    private fun counts(rows: List<QueryMeasurement>, kind: SqlKind): String =
        if (rows.size == 1) {
            (rows.single().shape?.count(kind) ?: 0).toString()
        } else {
            rows.joinToString { row ->
                "${row.size ?: "—"}→${row.shape?.count(kind) ?: 0}"
            }
        }

    /**
     * Классификация выводится из точек измерения. Единственная допустимая меняющаяся форма —
     * один UPDATE плана на изменённую строку при reconciliation.
     */
    private fun inferComplexity(rows: List<QueryMeasurement>): String {
        if (rows.all { it.statements.isEmpty() }) return "0 SQL"
        check(rows.none { it.statements.isEmpty() }) {
            "Смешаны SQL и zero-SQL точки для ${rows.first().owner}.${rows.first().method}"
        }
        if (rows.size == 1) return rows.single().complexity

        val fingerprints = rows.map { row ->
            row.statements.groupingBy(ExecutedStatement::fingerprint).eachCount()
        }
        if (fingerprints.distinct().size == 1) return "Θ(1)"

        if (rows.all { it.complexity == "Θ(n) Using UPDATE" }) {
            val fixedKinds = listOf(SqlKind.SELECT, SqlKind.INSERT, SqlKind.DELETE)
            check(fixedKinds.all { kind ->
                rows.map { it.shape?.count(kind) ?: 0 }.distinct().size == 1
            }) { "Reconciliation добавил не-UPDATE SQL при росте fixture" }
            val points = rows.map { row ->
                requireNotNull(row.size) to (row.shape?.count(SqlKind.UPDATE) ?: 0)
            }
            check(points.zipWithNext().all { (left, right) ->
                right.second - left.second == right.first - left.first
            }) { "Число UPDATE reconciliation не соответствует числу изменённых планов: $points" }
            return "Θ(n) Using UPDATE"
        }

        error(
            "Необъяснённый рост SQL для ${rows.first().owner}.${rows.first().method} " +
                "[${rows.first().branch}]: $fingerprints"
        )
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

private data class MeasurementKey(
    val owner: String,
    val method: String,
    val branch: String
)

private data class MeasurementGroup(
    val key: MeasurementKey,
    val rows: List<QueryMeasurement>,
    val complexity: String
)
