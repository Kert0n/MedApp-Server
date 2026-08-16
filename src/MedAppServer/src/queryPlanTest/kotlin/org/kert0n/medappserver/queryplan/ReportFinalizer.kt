package org.kert0n.medappserver.queryplan

import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Adds completed Gradle task results after queryPlanTest has written its own JUnit XML. */
object ReportFinalizer {
    @JvmStatic
    fun main(args: Array<String>) {
        val project = Path.of(args.singleOrNull() ?: System.getProperty("user.dir"))
        val reportDirectory = project.resolve("build/reports/query-plans")
        val json = reportDirectory.resolve("database-query-report.json")
        val markdown = reportDirectory.resolve("database-query-report.md")
        if (!Files.exists(json) || !Files.exists(markdown)) return

        val tasks = testTasks(project)
        val mapper = ObjectMapper()
        @Suppress("UNCHECKED_CAST")
        val root = mapper.readValue(json.toFile(), MutableMap::class.java) as MutableMap<String, Any>
        root["testTasks"] = tasks
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), root)

        val body = Files.readString(markdown).substringBefore("## Test tasks").trimEnd()
        Files.writeString(markdown, "$body\n\n${taskTable(tasks)}")
    }

    private fun taskTable(tasks: List<Map<String, Any>>): String = buildString {
        appendLine("## Test tasks")
        appendLine()
        appendLine("| Набор | Команда | Всего | Успешно | Пропущено | Ошибки | Время, с |")
        appendLine("|---|---|---:|---:|---:|---:|---:|")
        tasks.forEach { task ->
            appendLine(
                "| ${task["name"]} | `${task["command"]}` | ${task["total"]} | " +
                    "${task["successful"]} | ${task["skipped"]} | ${task["errors"]} | ${task["seconds"]} |"
            )
        }
    }

    private fun testTasks(project: Path): List<Map<String, Any>> =
        listOf("test", "queryPlanTest").mapNotNull { task ->
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
