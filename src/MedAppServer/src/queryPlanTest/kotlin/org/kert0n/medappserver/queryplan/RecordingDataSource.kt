package org.kert0n.medappserver.queryplan

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/** Оператор вместе со значениями, с которыми он реально ушёл в базу. */
data class ExecutedStatement(val sql: String, val parameters: List<Any?>) {
    val isSelect: Boolean get() = sql.trimStart().startsWith("select", ignoreCase = true)
}

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

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // Все JDBC setXxx имеют номер параметра первым аргументом.
            if (method.name.startsWith("set") && (args?.size ?: 0) >= 2) {
                (args!![0] as? Int)?.let { position -> parameters[position] = args[1] }
            }
            if (method.name == "clearParameters") parameters.clear()
            if (method.name.startsWith("execute")) {
                Recorded.add(ExecutedStatement(sql, parameters.values.toList()))
            }
            return method.invoke(target, *(args ?: emptyArray()))
        }
    }

    /** Собранные операторы. Статика, потому что DataSource создаётся контейнером Spring. */
    companion object Recorded {
        private val executed = mutableListOf<ExecutedStatement>()

        @Volatile
        private var recording = false

        fun add(statement: ExecutedStatement) {
            if (recording) synchronized(executed) { executed += statement }
        }

        /** Выполняет сценарий и возвращает операторы, которые он породил. */
        fun capture(scenario: () -> Unit): List<ExecutedStatement> {
            synchronized(executed) { executed.clear() }
            recording = true
            try {
                scenario()
            } finally {
                recording = false
            }
            return synchronized(executed) { executed.toList() }
        }
    }
}
