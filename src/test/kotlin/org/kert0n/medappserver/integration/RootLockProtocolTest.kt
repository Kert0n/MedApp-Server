package org.kert0n.medappserver.integration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Два режима блокировки корня ведут себя так, как обещано.
 *
 * Проверяется не текст запроса, а наблюдаемое поведение в настоящем PostgreSQL: совместимые
 * держатели друг друга не задерживают, исключительный ждёт их всех. Ожидание доказывается
 * `pg_blocking_pids`, а не задержкой потока — иначе тест зеленел бы по случайности.
 */
@PostgresIntegrationTest
class RootLockProtocolTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `совместимые держатели корня не ждут друг друга`() {
        val owner = dbHelper.freshUser("guard-parallel")
        val kit = dbHelper.freshMedKit(owner.id)

        val bothInside = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sides = (1..2).map {
                pool.submit<Throwable?> {
                    runCatching {
                        TransactionTemplate(transactionManager).execute {
                            medKitService.guard(setOf(kit.id), owner.id)
                            bothInside.countDown()
                            // Разойтись отсюда можно, только если корень держат оба сразу.
                            assertTrue(bothInside.await(10, TimeUnit.SECONDS), "вторая сторона не получила корень")
                        }
                    }.exceptionOrNull()
                }
            }
            sides.forEach { assertTrue(it.get(30, TimeUnit.SECONDS) == null, "совместимый режим не должен отказывать") }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `исключительный держатель ждёт совместимого`() {
        val owner = dbHelper.freshUser("guard-vs-lock")
        val kit = dbHelper.freshMedKit(owner.id)

        val guardTaken = CountDownLatch(1)
        val releaseGuard = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val shared = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        medKitService.guard(setOf(kit.id), owner.id)
                        guardTaken.countDown()
                        assertTrue(releaseGuard.await(10, TimeUnit.SECONDS), "исключительный не дошёл до ожидания")
                    }
                }.exceptionOrNull()
            }
            assertTrue(guardTaken.await(10, TimeUnit.SECONDS), "совместимый режим не взял корень")

            val backend = java.util.concurrent.ArrayBlockingQueue<Int>(1)
            val exclusive = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        backend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        medKitService.lock(setOf(kit.id), owner.id)
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(backend.poll(10, TimeUnit.SECONDS) ?: error("исключительный не начал транзакцию"))
            releaseGuard.countDown()

            assertTrue(shared.get(30, TimeUnit.SECONDS) == null, "совместимый обязан завершиться")
            assertTrue(exclusive.get(30, TimeUnit.SECONDS) == null, "исключительный обязан дождаться и пройти")
        } finally {
            releaseGuard.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `совместимый режим не выдаёт чужую аптечку`() {
        val owner = dbHelper.freshUser("guard-foreign-owner")
        val outsider = dbHelper.freshUser("guard-foreign-eve")
        val kit = dbHelper.freshMedKit(owner.id)

        assertRefusesAccess { medKitService.guard(setOf(kit.id), outsider.id) }
    }

    @Test
    fun `совместимый режим не отличает исчезнувшую аптечку от чужой`() {
        val owner = dbHelper.freshUser("guard-missing")

        assertRefusesAccess { medKitService.guard(setOf(Uuid.random()), owner.id) }
    }

    /**
     * Отказ обязан вылететь наружу транзакции, а не быть пойманным внутри неё.
     *
     * Поймать его внутри значило бы получить `UnexpectedRollbackException` на коммите: отказ уже
     * пометил транзакцию к откату. Заодно так видно, что наружу летит именно доменный отказ.
     */
    private fun assertRefusesAccess(command: () -> Unit) {
        assertThrows<NotAMember> {
            TransactionTemplate(transactionManager).execute { command() }
        }
    }

    /** Ждёт именно блокировку в PostgreSQL; задержка потока не считается доказательством. */
    private fun awaitDatabaseLock(backendPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject(
                "SELECT cardinality(pg_blocking_pids(?)) > 0",
                Boolean::class.java,
                backendPid
            ) == true
            if (blocked) return
            Thread.onSpinWait()
        }
        error("исключительная блокировка не встала в очередь за совместимой")
    }
}
