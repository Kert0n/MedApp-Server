package org.kert0n.medappserver.testutil

import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Записывает SQL, который порождает само хранилище.
 *
 * Переписать запрос в тесте руками было бы проще, но тогда измерялся бы этот переписанный
 * запрос, а не тот, что уходит в базу. Расхождение между ними — ровно тот случай, который
 * набор и должен ловить.
 *
 * Значения подставляются в текст: `EXPLAIN` нужен готовый оператор, а не подготовленный с
 * параметрами. Тому, кто считает запросы, текст не нужен вовсе — нужно их число, — но одна запись
 * на оба применения честнее двух похожих.
 *
 * Живёт в общей оснастке, потому что нужен обоим наборам: планы запросов смотрят на форму
 * оператора, гейты N+1 — на их количество.
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

        /**
         * То же, но со своей транзакцией — для тех, кто меряет не хранилище, а обращение целиком.
         *
         * Фасад открывает транзакцию с `REQUIRED` и присоединяется к этой, поэтому в запись
         * попадает весь его SQL. Совпадает при этом **число операторов**, а не поведение на
         * коммите: отложенного до коммита у нас нет, а журнал синхронизации пишется мимо базы.
         *
         * Обращение, не сделавшее ни одного запроса, — провал замера, а не успех: считать в нём
         * нечего, и зелёный такой гейт означал бы, что мерили не то.
         */
        fun inTransaction(transactionManager: PlatformTransactionManager, work: () -> Unit): List<String> {
            val statements = TransactionTemplate(transactionManager).execute { of(work) }.orEmpty()
            check(statements.isNotEmpty()) { "обращение не сделало ни одного запроса — мерить нечего" }
            return statements
        }
    }
}
