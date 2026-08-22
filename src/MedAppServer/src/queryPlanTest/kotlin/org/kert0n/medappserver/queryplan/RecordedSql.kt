package org.kert0n.medappserver.queryplan

import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Записывает SQL, который порождает само хранилище.
 *
 * Переписать запрос в тесте руками было бы проще, но тогда измерялся бы этот переписанный
 * запрос, а не тот, что уходит в базу. Расхождение между ними — ровно тот случай, который
 * набор и должен ловить.
 *
 * Значения подставляются в текст: `EXPLAIN` нужен готовый оператор, а не подготовленный с
 * параметрами.
 */
class RecordedSql : SqlLogger {

    private val statements = mutableListOf<String>()

    override fun log(context: StatementContext, transaction: Transaction) {
        statements += context.expandArgs(transaction)
    }

    companion object {
        /** Выполняет чтение и отдаёт запросы, которые оно на самом деле сделало. */
        fun of(read: () -> Unit): List<String> {
            val recorder = RecordedSql()
            val transaction = TransactionManager.current()
            // Логгер снимается вместе с транзакцией: отдельного снятия у Exposed нет, а
            // каждое измерение и так идёт в своей.
            transaction.addLogger(recorder)
            read()
            return recorder.statements.toList()
        }
    }
}
