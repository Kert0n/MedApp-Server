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

    /**
     * Заворачивает готовый DataSource записывающей обёрткой.
     *
     * Через `BeanPostProcessor`, а не отдельным `@Primary`-бином: бин, который принимает
     * `DataSource` и сам является `DataSource`, замыкает цикл зависимостей, и контекст не
     * поднимается. Здесь же обёртка навешивается на уже созданный экземпляр — тот, что
     * собрал `@ServiceConnection` из контейнера.
     */
    @Bean
    fun recordingDataSourceWrapper() = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && bean !is RecordingDataSource) RecordingDataSource(bean) else bean
    }

    /**
     * Тот же образ, что в проде и в обычных тестах.
     *
     * Планировщик — часть проверяемого поведения, поэтому версия обязана совпадать: на другой
     * минорной версии план может отличаться, и тест начал бы проверять чужую СУБД.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:18.3-trixie"))
            .withInitScript("init-pg-trgm.sql")
            // Локаль та же, что в compose: pg_trgm определяет «буквенность» символа через
            // LC_CTYPE, и в локали C кириллица буквой не считается — триграммы из русского
            // текста не извлекаются вовсе. Наследовать это от образа значит проверять не ту
            // конфигурацию, что в проде.
            .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --lc-ctype=en_US.utf8 --lc-collate=en_US.utf8")
}

/**
 * Разбор плана из `EXPLAIN (FORMAT JSON)`.
 *
 * Проверяются свойства формы плана, а не время: настоящей нагрузки у проекта нет, и
 * миллисекунды на синтетике не значат ничего. А `Seq Scan` по таблице на десятки тысяч строк
 * значит — и от объёма данных этот факт не зависит.
 */
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

/**
 * Строит настоящий план оператора: значения параметров подставляются литералами.
 *
 * `ANALYZE` не используется — план нужен, а не время: настоящей нагрузки у проекта нет, и
 * миллисекунды на синтетике зависят от машины и кеша страниц. Форма плана не зависит.
 *
 * Ранний вариант обходился `GENERIC_PLAN` без значений, и это оказалось ошибкой: обобщённый
 * план строится без знания избирательности, поэтому для триграммного поиска планировщик
 * выбирал `Seq Scan`, хотя с настоящим значением берёт индексы. Набор падал и мерял не то.
 */
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
        runCatching {
            // enable_seqscan=off не «ускоряет запрос», а отвечает на другой вопрос: способен
            // ли запрос вообще воспользоваться индексом. Выбор плана — дело планировщика и
            // объёма данных, а вот запрос, который в индекс не попадает ни при каких
            // настройках, — это дефект формы запроса, и он от объёма не зависит.
            if (forceIndexes) jdbc.execute("SET enable_seqscan = off")
            jdbc.executeQuery("EXPLAIN (FORMAT JSON, COSTS OFF) $filled")
                .use { rows -> if (rows.next()) rows.getString(1) else null }
        }.getOrElse { error ->
            // Молча возвращать null нельзя: тест показал бы «разобрано планов 0» и выглядел
            // бы так, будто запросов не было.
            println("  ! EXPLAIN не удался: ${error.message?.take(160)}")
            null
        }
    } ?: return null
    if (forceIndexes) connection.createStatement().use { it.execute("RESET enable_seqscan") }

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
