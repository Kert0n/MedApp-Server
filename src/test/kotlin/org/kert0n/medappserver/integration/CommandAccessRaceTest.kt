package org.kert0n.medappserver.integration

import java.math.BigDecimal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.application.ReservationApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Доступ удерживается не прочитанным объектом, а публичной командой на весь срок транзакции.
 *
 * Lifecycle-транзакция удаляет membership, но задерживает commit. Другая транзакция всё ещё
 * видит старую строку своим обычным SELECT, поэтому проверка только чтением ошибочно пропускает
 * команду. Правильная команда встаёт за корневой блокировкой и после commit получает
 * [NotAMember]. Ожидание подтверждается PostgreSQL, а не задержкой потока.
 */
@PostgresIntegrationTest
class CommandAccessRaceTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugs: DrugApplicationService
    @Autowired private lateinit var medKits: MedKitApplicationService
    @Autowired private lateinit var reservations: ReservationApplicationService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `команды упаковки ждут отзыв membership`() {
        assertWaitsForLeave("update") { fixture ->
            drugs.update(
                fixture.drugId,
                DrugPatchRequest(name = "Changed", version = fixture.drugVersion),
                fixture.memberId
            )
        }
        assertWaitsForLeave("consume") { fixture ->
            drugs.recordIntake(
                fixture.drugId,
                IntakeRequest(BigDecimal.ONE, fixture.drugVersion),
                fixture.memberId
            )
        }
        assertWaitsForLeave("delete") { fixture ->
            drugs.delete(fixture.drugId, fixture.drugVersion, fixture.memberId)
        }
        assertWaitsForLeave("sync") { fixture ->
            drugs.synchronise(
                fixture.drugId,
                Uuid.random(),
                DrugSyncRequest(consumed = BigDecimal.ONE, drugVersion = fixture.drugVersion),
                fixture.memberId
            )
        }
    }

    @Test
    fun `команды брони ждут отзыв membership`() {
        assertWaitsForLeave("reserve") { fixture ->
            reservations.create(
                fixture.memberId,
                ReservationCreateRequest(fixture.drugId, BigDecimal("3"), fixture.reservationsVersion)
            )
        }
        assertWaitsForLeave("change-reservation", withReservation = true) { fixture ->
            reservations.changeTo(
                fixture.memberId,
                fixture.drugId,
                ReservationPatchRequest(BigDecimal("6"), fixture.reservationsVersion)
            )
        }
        assertWaitsForLeave("cancel-reservation", withReservation = true) { fixture ->
            reservations.cancel(fixture.memberId, fixture.drugId, fixture.reservationsVersion)
        }
    }

    @Test
    fun `отзыв membership ждёт уже начатую команду`() {
        val owner = dbHelper.freshUser("command-first-owner")
        val member = dbHelper.freshUser("command-first-member")
        val kit = dbHelper.freshMedKit(owner.id)
        dbHelper.join(kit.id, owner.id, member.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 20.0)

        val commandFinished = CountDownLatch(1)
        val allowCommandCommit = CountDownLatch(1)
        val leaveBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        drugs.update(drug.id, DrugPatchRequest(name = "Before leave", version = drug.version), member.id)
                        commandFinished.countDown()
                        assertTrue(allowCommandCommit.await(10, TimeUnit.SECONDS), "leave не дошёл до корня")
                    }
                }.exceptionOrNull()
            }
            assertTrue(commandFinished.await(10, TimeUnit.SECONDS), "команда не завершила запись")

            val leave = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        leaveBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        medKits.leave(kit.id, member.id)
                    }
                }.exceptionOrNull()
            }
            awaitDatabaseLock(leaveBackend.poll(10, TimeUnit.SECONDS) ?: error("leave не начался"), "leave")
            allowCommandCommit.countDown()

            assertNull(command.get(30, TimeUnit.SECONDS)?.rootCause(), "начатая команда обязана завершиться")
            assertNull(leave.get(30, TimeUnit.SECONDS)?.rootCause(), "leave обязан дождаться команды")
        } finally {
            allowCommandCommit.countDown()
            pool.shutdownNow()
        }

        assertTrue(!dbHelper.isMember(kit.id, member.id))
    }

    @Test
    fun `приглашение ждёт отзыв membership`() {
        assertWaitsForLeave("invite") { fixture ->
            medKits.invite(fixture.medKitId, fixture.memberId)
        }
    }

    private fun assertWaitsForLeave(
        name: String,
        withReservation: Boolean = false,
        command: (Fixture) -> Any?
    ) {
        val owner = dbHelper.freshUser("command-access-$name-owner")
        val member = dbHelper.freshUser("command-access-$name-member")
        val kit = dbHelper.freshMedKit(owner.id)
        dbHelper.join(kit.id, owner.id, member.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 20.0)
        if (withReservation) dbHelper.reserve(member.id, drug.id, BigDecimal("4"))
        val fixture = Fixture(
            kit.id,
            member.id,
            drug.id,
            drug.version,
            dbHelper.storedReservationsVersion(drug.id)
        )

        val membershipDeleted = CountDownLatch(1)
        val allowLeaveCommit = CountDownLatch(1)
        val commandBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val leave = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        medKits.leave(kit.id, member.id)
                        membershipDeleted.countDown()
                        assertTrue(
                            allowLeaveCommit.await(10, TimeUnit.SECONDS),
                            "$name не дошёл до ожидания корня"
                        )
                    }
                }.exceptionOrNull()
            }
            assertTrue(membershipDeleted.await(10, TimeUnit.SECONDS), "leave не удалил membership")

            val attempted = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        commandBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        command(fixture)
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(
                commandBackend.poll(10, TimeUnit.SECONDS) ?: error("$name не начал транзакцию"),
                name
            )
            allowLeaveCommit.countDown()

            assertNull(leave.get(30, TimeUnit.SECONDS)?.rootCause(), "leave обязан завершиться")
            val failure = attempted.get(30, TimeUnit.SECONDS)?.rootCause()
            assertTrue(failure is NotAMember, "$name после отзыва доступа обязан получить NotAMember: $failure")
        } finally {
            allowLeaveCommit.countDown()
            pool.shutdownNow()
        }
    }

    private fun awaitDatabaseLock(backendPid: Int, command: String) {
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
        error("$command не ждёт lifecycle-блокировку корня")
    }

    private data class Fixture(
        val medKitId: Uuid,
        val memberId: Uuid,
        val drugId: Uuid,
        val drugVersion: Long,
        val reservationsVersion: Long
    )
}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
