package org.kert0n.medappserver.testutil

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Две транзакции, у которых чтение одной заведомо предшествует записи другой.
 *
 * Гонку нельзя проверять запуском двух потоков в надежде, что они пересекутся: тест на удаче
 * либо пропускает поломку, либо мигает. Здесь перекрытие задано явно — «медленная» транзакция
 * читает состояние, ждёт, пока «быстрая» полностью отработает и закоммитится, и только потом
 * пишет по прочитанному. Это ровно потерянное обновление, от которого защищает версия.
 *
 * Транзакция живёт в потоке, поэтому медленная сторона уезжает в свой; защёлки держат порядок.
 */
@Component
class InterleavedTransactions(transactionManager: PlatformTransactionManager) {

    private val transactions = TransactionTemplate(transactionManager)

    /**
     * Запускает [read] в отдельной транзакции, затем целиком выполняет [meanwhile], и лишь после
     * этого — [write] в той же транзакции, что и [read].
     *
     * Возвращает то, чем закончилась медленная сторона: `null`, если она прошла, иначе её отказ.
     */
    fun <T> lostUpdate(read: () -> T, meanwhile: () -> Unit, write: (T) -> Unit): Throwable? {
        val hasRead = CountDownLatch(1)
        val mayWrite = CountDownLatch(1)
        var failure: Throwable? = null

        val slow = Thread {
            failure = runCatching {
                transactions.executeWithoutResult {
                    val state = read()
                    hasRead.countDown()
                    check(mayWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "быстрая сторона не завершилась" }
                    write(state)
                }
            }.exceptionOrNull()
        }

        slow.start()
        check(hasRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "медленная сторона не прочитала состояние" }
        try {
            meanwhile()
        } finally {
            mayWrite.countDown()
        }
        slow.join(TIMEOUT_SECONDS * 1000)

        return failure
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
