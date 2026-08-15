package org.kert0n.medappserver.queryplan

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Оператор вместе со значениями, с которыми он реально ушёл в базу. */
data class ExecutedStatement(val sql: String, val parameters: List<Any?>) {
    val kind: SqlKind get() = when (sql.trimStart().substringBefore(' ').uppercase()) {
        "SELECT" -> SqlKind.SELECT
        "INSERT" -> SqlKind.INSERT
        "UPDATE" -> SqlKind.UPDATE
        "DELETE" -> SqlKind.DELETE
        else -> SqlKind.OTHER
    }
    val isSelect: Boolean get() = kind == SqlKind.SELECT
    val fingerprint: String get() {
        val compact = sql.trim().replace(Regex("\\s+"), " ")
        return Regex("(?i)\\bin\\s*\\((?:\\s*\\?\\s*,)*\\s*\\?\\s*\\)")
            .replace(compact, "in (?*)")
            .lowercase()
    }

    fun diagnostic(): String = "${kind.name.padEnd(6)} $fingerprint parameters=$parameters"
}

enum class SqlKind { SELECT, INSERT, UPDATE, DELETE, OTHER }

data class QueryShape(
    val total: Int,
    val byKind: Map<SqlKind, Int>,
    val fingerprints: Map<String, Int>
) {
    init { require(total > 0) { "DB scenario executed zero SQL statements" } }
    fun count(kind: SqlKind): Int = byKind[kind] ?: 0
}

fun List<ExecutedStatement>.queryShape() = QueryShape(
    size,
    groupingBy(ExecutedStatement::kind).eachCount(),
    groupingBy(ExecutedStatement::fingerprint).eachCount()
)

/** JDBC-обёртка, записывающая SQL вместе с параметрами для последующего EXPLAIN. */
class RecordingDataSource(private val delegate: DataSource) : DataSource by delegate {

    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        wrap(delegate.getConnection(username, password))

    private fun wrap(connection: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            ConnectionHandler(connection)
        ) as Connection

    private class ConnectionHandler(private val target: Connection) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val result = method.invoke(target, *(args ?: emptyArray()))
            if (method.name == "prepareStatement" && result is PreparedStatement) {
                val sql = args?.firstOrNull() as? String ?: return result
                return Proxy.newProxyInstance(
                    PreparedStatement::class.java.classLoader,
                    arrayOf(PreparedStatement::class.java),
                    StatementHandler(result, sql)
                )
            }
            return result
        }
    }

    private class StatementHandler(
        private val target: PreparedStatement,
        private val sql: String
    ) : InvocationHandler {

        private val parameters = sortedMapOf<Int, Any?>()
        private val batches = mutableListOf<List<Any?>>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // Все JDBC setXxx имеют номер параметра первым аргументом.
            if (method.name.startsWith("set") && (args?.size ?: 0) >= 2) {
                (args!![0] as? Int)?.let { position ->
                    parameters[position] = if (method.name == "setNull") null else args[1]
                }
            }
            if (method.name == "clearParameters") parameters.clear()
            if (method.name == "addBatch") batches += parameters.values.toList()
            if (method.name == "clearBatch") batches.clear()
            if (method.name == "executeBatch" || method.name == "executeLargeBatch") {
                (batches.ifEmpty { listOf(parameters.values.toList()) })
                    .forEach { Recorded.add(ExecutedStatement(sql, it)) }
                batches.clear()
            } else if (method.name.startsWith("execute")) {
                Recorded.add(ExecutedStatement(sql, parameters.values.toList()))
            }
            return method.invoke(target, *(args ?: emptyArray()))
        }
    }

    /** Собранные операторы. Статика, потому что DataSource создаётся контейнером Spring. */
    companion object Recorded {
        private val executed = mutableListOf<ExecutedStatement>()

        private val recording = AtomicBoolean(false)

        fun add(statement: ExecutedStatement) {
            if (recording.get()) synchronized(executed) { executed += statement }
        }

        /** Выполняет сценарий и возвращает операторы, которые он породил. */
        fun capture(scenario: () -> Unit): List<ExecutedStatement> {
            check(recording.compareAndSet(false, true)) {
                "RecordingDataSource measurements cannot be nested or concurrent"
            }
            synchronized(executed) { executed.clear() }
            try {
                scenario()
            } finally {
                recording.set(false)
            }
            return synchronized(executed) { executed.toList() }
        }
    }
}

fun List<ExecutedStatement>.diagnostic(): String = buildString {
    appendLine("SQL operators: ${this@diagnostic.size}")
    this@diagnostic.groupingBy(ExecutedStatement::fingerprint).eachCount()
        .filterValues { it > 1 }
        .forEach { (fingerprint, count) -> appendLine("  repeated x$count: $fingerprint") }
    this@diagnostic.forEach { appendLine("  ${it.diagnostic()}") }
}
