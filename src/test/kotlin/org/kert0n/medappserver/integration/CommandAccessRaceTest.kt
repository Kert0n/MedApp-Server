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
        DRUG_COMMANDS.forEach { assertWaitsForRevocation(it, Revocation.LEAVE) }
    }

    @Test
    fun `команды брони ждут отзыв membership`() {
        RESERVATION_COMMANDS.forEach { assertWaitsForRevocation(it, Revocation.LEAVE) }
    }

    /**
     * Глобальное удаление отзывает доступ иначе, чем выход.
     *
     * Строка membership уходит каскадом от корня, а не удаляется сама, и сам корень исчезает.
     * Для команды это обязано выглядеть так же: доменный отказ, а не нарушение внешнего ключа.
     */
    @Test
    fun `команды упаковки ждут удаление аптечки`() {
        DRUG_COMMANDS.forEach { assertWaitsForRevocation(it, Revocation.DELETE) }
    }

    @Test
    fun `команды брони ждут удаление аптечки`() {
        RESERVATION_COMMANDS.forEach { assertWaitsForRevocation(it, Revocation.DELETE) }
    }

    /** Обратный порядок: команда успела начаться, и отзыв доступа обязан её дождаться. */
    @Test
    fun `отзыв membership ждёт уже начатую команду`() {
        (DRUG_COMMANDS + RESERVATION_COMMANDS).forEach { assertRevocationWaitsFor(it) }
    }

    @Test
    fun `приглашение ждёт отзыв membership`() {
        assertWaitsForRevocation(
            Command("invite", withReservation = false) { fixture ->
                medKits.invite(fixture.medKitId, fixture.memberId)
            },
            Revocation.LEAVE
        )
    }

    /**
     * Отзыв доступа коммитится первым; команда обязана его дождаться и получить отказ.
     *
     * Отзыв удаляет membership и задерживает commit. Обычный SELECT команды всё ещё видит старую
     * строку, поэтому проверка одним чтением её бы пропустила. Команда встаёт за корневой
     * блокировкой — ожидание подтверждается `pg_blocking_pids`, а не задержкой потока.
     */
    private fun assertWaitsForRevocation(command: Command, revocation: Revocation) {
        val name = "${command.name}-${revocation.tag}"
        val fixture = fixture(name, command.withReservation)

        val accessRevoked = CountDownLatch(1)
        val allowRevocationCommit = CountDownLatch(1)
        val commandBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val revoke = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        revocation.apply(this@CommandAccessRaceTest, fixture)
                        accessRevoked.countDown()
                        assertTrue(
                            allowRevocationCommit.await(10, TimeUnit.SECONDS),
                            "$name не дошёл до ожидания корня"
                        )
                    }
                }.exceptionOrNull()
            }
            assertTrue(accessRevoked.await(10, TimeUnit.SECONDS), "${revocation.tag} не отозвал доступ")

            val attempted = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        commandBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        command.run(this@CommandAccessRaceTest, fixture)
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(
                commandBackend.poll(10, TimeUnit.SECONDS) ?: error("$name не начал транзакцию"),
                name
            )
            allowRevocationCommit.countDown()

            assertNull(revoke.get(30, TimeUnit.SECONDS)?.rootCause(), "${revocation.tag} обязан завершиться")
            val failure = attempted.get(30, TimeUnit.SECONDS)?.rootCause()
            assertTrue(failure is NotAMember, "$name после отзыва доступа обязан получить NotAMember: $failure")
        } finally {
            allowRevocationCommit.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * Обратный порядок: команда уже записала, отзыв доступа обязан её дождаться.
     *
     * Здесь проверяется не отказ, а отсутствие обгона: обе транзакции законны, ни одна не должна
     * упереться в нарушение ключа или deadlock.
     */
    private fun assertRevocationWaitsFor(command: Command) {
        val name = "${command.name}-first"
        val fixture = fixture(name, command.withReservation)

        val commandWrote = CountDownLatch(1)
        val allowCommandCommit = CountDownLatch(1)
        val leaveBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempted = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        command.run(this@CommandAccessRaceTest, fixture)
                        commandWrote.countDown()
                        assertTrue(allowCommandCommit.await(10, TimeUnit.SECONDS), "$name: выход не дошёл до корня")
                    }
                }.exceptionOrNull()
            }
            assertTrue(commandWrote.await(10, TimeUnit.SECONDS), "$name не завершил запись")

            val leave = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        leaveBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        medKits.leave(fixture.medKitId, fixture.memberId)
                    }
                }.exceptionOrNull()
            }
            awaitDatabaseLock(leaveBackend.poll(10, TimeUnit.SECONDS) ?: error("$name: выход не начался"), name)
            allowCommandCommit.countDown()

            assertNull(attempted.get(30, TimeUnit.SECONDS)?.rootCause(), "$name обязан завершиться")
            assertNull(leave.get(30, TimeUnit.SECONDS)?.rootCause(), "$name: выход обязан дождаться команды")
        } finally {
            allowCommandCommit.countDown()
            pool.shutdownNow()
        }

        assertTrue(!dbHelper.isMember(fixture.medKitId, fixture.memberId), "$name: участник обязан выйти")
    }

    private fun fixture(name: String, withReservation: Boolean): Fixture {
        val owner = dbHelper.freshUser("command-access-$name-owner")
        val member = dbHelper.freshUser("command-access-$name-member")
        val kit = dbHelper.freshMedKit(owner.id)
        dbHelper.join(kit.id, owner.id, member.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 20.0)
        if (withReservation) dbHelper.reserve(member.id, drug.id, BigDecimal("4"))
        return Fixture(
            kit.id,
            owner.id,
            member.id,
            drug.id,
            drug.version,
            dbHelper.storedReservationsVersion(drug.id)
        )
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
        val ownerId: Uuid,
        val memberId: Uuid,
        val drugId: Uuid,
        val drugVersion: Long,
        val reservationsVersion: Long
    )

    /** Команда участника — как данные, чтобы каждый порядок проверялся всем набором сразу. */
    private class Command(
        val name: String,
        val withReservation: Boolean,
        val run: CommandAccessRaceTest.(Fixture) -> Any?
    )

    /**
     * Чем именно отзывается доступ.
     *
     * Выход удаляет строку membership сам; глобальное удаление уносит её каскадом вместе с
     * корнем. Пути в базе разные, а для команды результат обязан быть одним и тем же.
     */
    private enum class Revocation(val tag: String) {
        LEAVE("leave") {
            override fun apply(test: CommandAccessRaceTest, fixture: Fixture) =
                test.medKits.leave(fixture.medKitId, fixture.memberId)
        },
        DELETE("delete-medkit") {
            override fun apply(test: CommandAccessRaceTest, fixture: Fixture) =
                test.medKits.delete(fixture.medKitId, fixture.ownerId)
        };

        abstract fun apply(test: CommandAccessRaceTest, fixture: Fixture)
    }

    private companion object {
        val DRUG_COMMANDS = listOf(
            Command("update", withReservation = false) { fixture ->
                drugs.update(
                    fixture.drugId,
                    DrugPatchRequest(name = "Changed", version = fixture.drugVersion),
                    fixture.memberId
                )
            },
            Command("consume", withReservation = false) { fixture ->
                drugs.recordIntake(
                    fixture.drugId,
                    IntakeRequest(BigDecimal.ONE, fixture.drugVersion),
                    fixture.memberId
                )
            },
            Command("delete", withReservation = false) { fixture ->
                drugs.delete(fixture.drugId, fixture.drugVersion, fixture.memberId)
            },
            Command("sync", withReservation = false) { fixture ->
                drugs.synchronise(
                    fixture.drugId,
                    Uuid.random(),
                    DrugSyncRequest(consumed = BigDecimal.ONE, drugVersion = fixture.drugVersion),
                    fixture.memberId
                )
            }
        )

        val RESERVATION_COMMANDS = listOf(
            Command("reserve", withReservation = false) { fixture ->
                reservations.create(
                    fixture.memberId,
                    ReservationCreateRequest(fixture.drugId, BigDecimal("3"), fixture.reservationsVersion)
                )
            },
            Command("change-reservation", withReservation = true) { fixture ->
                reservations.changeTo(
                    fixture.memberId,
                    fixture.drugId,
                    ReservationPatchRequest(BigDecimal("6"), fixture.reservationsVersion)
                )
            },
            Command("cancel-reservation", withReservation = true) { fixture ->
                reservations.cancel(fixture.memberId, fixture.drugId, fixture.reservationsVersion)
            }
        )
    }
}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
