package org.kert0n.medappserver.queryplan

import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

data class NPlusOneMeasurement(
    val owner: String,
    val method: String,
    val size: Int,
    val statistics: HibernateStatisticsSnapshot,
    val complexity: String
)

class NPlusOneReport(private val objectMapper: ObjectMapper) {
    private val measurements = mutableListOf<NPlusOneMeasurement>()

    fun record(measurement: NPlusOneMeasurement) {
        measurements += measurement
    }

    fun write(outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        val rows = measurements.sortedWith(
            compareBy(NPlusOneMeasurement::owner, NPlusOneMeasurement::method)
                .thenBy(NPlusOneMeasurement::size)
        )
        Files.writeString(outputDirectory.resolve("n-plus-one-report.md"), markdown(rows))
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputDirectory.resolve("n-plus-one-report.json").toFile(), rows)
    }

    private fun markdown(rows: List<NPlusOneMeasurement>): String = buildString {
        appendLine("# Hibernate N+1 report")
        appendLine()
        appendLine(
            "Метрики получены из `SessionFactory.statistics`; основной бюджет — " +
                "`prepareStatementCount`."
        )
        appendLine()
        appendLine(
            "| Сервис.метод | Размер | Prepared statements | Query executions | " +
                "Entity fetches | Collection fetches | Асимптотика |"
        )
        appendLine("|---|---:|---:|---:|---:|---:|---|")
        rows.forEach { row ->
            appendLine(
                "| `${row.owner}.${row.method}` | ${row.size} | " +
                    "${row.statistics.preparedStatements} | ${row.statistics.queryExecutions} | " +
                    "${row.statistics.entityFetches} | ${row.statistics.collectionFetches} | " +
                    "${row.complexity} |"
            )
        }
    }
}
