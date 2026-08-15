package org.kert0n.medappserver.queryplan

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

@TestConfiguration(proxyBeanMethods = false)
class QueryPlanContainerConfiguration {

    /** Оборачивает созданный Spring DataSource без объявления конкурирующего primary-бина. */
    @Bean
    fun recordingDataSourceWrapper() = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && bean !is RecordingDataSource) RecordingDataSource(bean) else bean
    }

    /** Версия PostgreSQL и locale совпадают с рабочей конфигурацией приложения. */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:18.3-trixie"))
            .withInitScript("init-pg-trgm.sql")
            .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --lc-ctype=en_US.utf8 --lc-collate=en_US.utf8")
}

/** Разобранное дерево `EXPLAIN (FORMAT JSON)`. */
class QueryPlan(private val root: Map<*, *>) {

    private val nodes: List<Map<*, *>> by lazy {
        buildList {
            fun walk(node: Map<*, *>) {
                add(node)
                (node["Plans"] as? List<*>)?.forEach { child ->
                    (child as? Map<*, *>)?.let(::walk)
                }
            }
            walk(root)
        }
    }

    /** Типы узлов: Seq Scan, Index Scan, Bitmap Heap Scan, LockRows и прочее. */
    val nodeTypes: List<String> get() = nodes.mapNotNull { it["Node Type"] as? String }

    /** Индексы, задействованные планом. */
    val indexes: List<String> get() = nodes.mapNotNull { it["Index Name"] as? String }

    /** Таблицы, прочитанные последовательным сканированием. */
    val sequentiallyScanned: List<String>
        get() = nodes.filter { it["Node Type"] == "Seq Scan" }
            .mapNotNull { it["Relation Name"] as? String }

    override fun toString(): String = nodeTypes.joinToString(" -> ")
}

/** Строит план без `ANALYZE`, подставляя фактические JDBC-параметры. */
fun explain(
    connection: Connection,
    objectMapper: ObjectMapper,
    statement: ExecutedStatement,
    forceIndexes: Boolean = false
): QueryPlan? {
    var filled = statement.sql
    statement.parameters.forEach { value ->
        filled = filled.replaceFirst("?", literal(value))
    }
    if ("?" in filled) return null

    val json = connection.createStatement().use { jdbc ->
        try {
            if (forceIndexes) jdbc.execute("SET enable_seqscan = off")
            jdbc.executeQuery("EXPLAIN (FORMAT JSON, COSTS OFF) $filled")
                .use { rows -> if (rows.next()) rows.getString(1) else null }
        } finally {
            if (forceIndexes) jdbc.execute("RESET enable_seqscan")
        }
    } ?: return null

    val parsed = objectMapper.readValue(json, List::class.java)
    val plan = (parsed.firstOrNull() as? Map<*, *>)?.get("Plan") as? Map<*, *> ?: return null
    return QueryPlan(plan)
}

/** Литерал для подстановки. UUID и строки экранируются, числа и NULL идут как есть. */
private fun literal(value: Any?): String = when (value) {
    null -> "NULL"
    is Number, is Boolean -> value.toString()
    else -> "'" + value.toString().replace("'", "''") + "'"
}

/** Соединение для EXPLAIN: своё, чтобы не мешать транзакциям приложения. */
fun openExplainConnection(container: PostgreSQLContainer): Connection =
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
