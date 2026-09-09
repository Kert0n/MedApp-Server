package org.kert0n.medappserver.integration

import java.math.BigDecimal
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.ReservationSyncRequest
import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.orchestrator.MedKitInviting
import org.kert0n.medappserver.services.orchestrator.MedKitJoining
import org.kert0n.medappserver.services.aggregate.MedKitAccessService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.orchestrator.ReservationChanging
import org.kert0n.medappserver.services.orchestrator.ReservationPlacement
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Гонки заводятся настоящими параллельными транзакциями.
 *
 * Имитация — прочитать, потом отдельной транзакцией переписать, потом дописать прочитанным —
 * доказывает только предикат в `UPDATE`. Здесь обе стороны держат транзакции одновременно.
 * Упаковки и снимки броней проверяют optimistic locking: одна сторона получает отказ, а состояние
 * совпадает с результатом победившей. Membership проверяет другой протокол: корень сериализует
 * join, leave и delete, не превращая изменения разных строк в конфликт версий.
 */
@PostgresIntegrationTest
class OptimisticRaceTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var inviting: MedKitInviting
    @Autowired private lateinit var joining: MedKitJoining
    @Autowired private lateinit var medKitAccess: MedKitAccessService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var drugApplicationService: DrugApplicationService
    @Autowired private lateinit var medKitApplicationService: MedKitApplicationService
    @Autowired private lateinit var placement: ReservationPlacement
    @Autowired private lateinit var changing: ReservationChanging
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `списание против правки количества`() {
        val owner = dbHelper.freshUser("race-consume")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 100.0)

        val outcome = race(
            { sync ->
                val read = drugService.get(drug.id, owner.id)
                sync()
                drugService.consume(read, BigDecimal("10"), read.version)
            },
            { sync ->
                val read = drugService.get(drug.id, owner.id)
                sync()
                drugService.update(read, DrugEdit(stated = read.version, quantity = BigDecimal("50")))
            }
        )

        outcome.assertOneLost()
        val left = dbHelper.drugQuantity(drug.id)!!
        assertTrue(
            left.compareTo(BigDecimal("90")) == 0 || left.compareTo(BigDecimal("50")) == 0,
            "состояние обязано совпасть с результатом победившей стороны, а не смешать оба: $left"
        )
    }

    @Test
    fun `двое последних выходят одновременно`() {
        val alice = dbHelper.freshUser("race-leave-a")
        val bob = dbHelper.freshUser("race-leave-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)

        val outcome = race(
            { sync ->
                sync()
                medKitApplicationService.leave(kit.id, alice.id)
            },
            { sync ->
                sync()
                medKitApplicationService.leave(kit.id, bob.id)
            }
        )

        assertTrue(outcome.failures.isEmpty(), "оба выхода должны завершиться: ${outcome.failures}")
        assertNull(dbHelper.medKit(kit.id), "последний выход обязан удалить аптечку")
    }

    @Test
    fun `перенос против выхода участника`() {
        val alice = dbHelper.freshUser("race-move-a")
        val bob = dbHelper.freshUser("race-move-b")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(source.id, quantity = 20.0)

        val outcome = race(
            { sync ->
                val read = drugService.get(drug.id, alice.id)
                sync()
                drugService.moveTo(read, target.id, read.version)
            },
            { sync ->
                sync()
                medKitApplicationService.leave(source.id, bob.id)
            }
        )

        assertTrue(outcome.failures.isEmpty(), "независимые команды должны завершиться: ${outcome.failures}")
        assertEquals(target.id, dbHelper.requireDrug(drug.id).medKitId)
    }

    /**
     * Выход из цели обязан сериализоваться со всем переносом, а не только с последним UPDATE.
     *
     * Leave удаляет membership, но держит транзакцию открытой. Старая реализация успевает
     * прочитать ещё видимую строку membership, сохраняет бронь и останавливается только на
     * каскадном внешнем ключе. Исправленная ждёт корень до чтения membership и после commit
     * удаляет уже несовместимую бронь.
     */
    @Test
    fun `одиночный перенос ждёт выход из целевой аптечки до проверки броней`() {
        val alice = dbHelper.freshUser("race-move-target-a")
        val bob = dbHelper.freshUser("race-move-target-b")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        dbHelper.join(target.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(source.id, quantity = 20.0)
        dbHelper.reserve(bob.id, drug.id, BigDecimal("5"))

        val membershipDeleted = CountDownLatch(1)
        val allowLeaveCommit = CountDownLatch(1)
        val moveBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val leave = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        medKitApplicationService.leave(target.id, bob.id)
                        membershipDeleted.countDown()
                        assertTrue(allowLeaveCommit.await(10, TimeUnit.SECONDS), "перенос не дошёл до блокировки")
                    }
                }.exceptionOrNull()
            }
            assertTrue(membershipDeleted.await(10, TimeUnit.SECONDS), "leave не удалил membership")

            val move = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        moveBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        drugApplicationService.moveToMedKit(drug.id, target.id, drug.version, alice.id)
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(moveBackend.poll(10, TimeUnit.SECONDS) ?: error("перенос не начал транзакцию"))
            allowLeaveCommit.countDown()

            assertNull(leave.get(30, TimeUnit.SECONDS)?.rootCause(), "выход должен завершиться")
            assertNull(move.get(30, TimeUnit.SECONDS)?.rootCause(), "перенос должен продолжиться после выхода")
        } finally {
            allowLeaveCommit.countDown()
            pool.shutdownNow()
        }

        assertEquals(target.id, dbHelper.requireDrug(drug.id).medKitId)
        assertNull(dbHelper.userReservation(bob.id, drug.id), "бронь вышедшего из цели должна быть снята")
        assertTrue(!dbHelper.isMember(target.id, bob.id))
    }

    @Test
    fun `встречные одиночные переносы блокируют аптечки в одном порядке`() {
        val alice = dbHelper.freshUser("race-opposite-moves")
        val firstKit = dbHelper.freshMedKit(alice.id)
        val secondKit = dbHelper.freshMedKit(alice.id)
        val firstDrug = dbHelper.freshDrug(firstKit.id, quantity = 10.0)
        val secondDrug = dbHelper.freshDrug(secondKit.id, quantity = 20.0)

        val outcome = race(
            { sync -> sync(); drugApplicationService.moveToMedKit(firstDrug.id, secondKit.id, firstDrug.version, alice.id) },
            { sync -> sync(); drugApplicationService.moveToMedKit(secondDrug.id, firstKit.id, secondDrug.version, alice.id) }
        )

        assertTrue(outcome.failures.isEmpty(), "встречные переносы не должны образовать deadlock: ${outcome.failures}")
        assertEquals(secondKit.id, dbHelper.requireDrug(firstDrug.id).medKitId)
        assertEquals(firstKit.id, dbHelper.requireDrug(secondDrug.id).medKitId)
    }

    @Test
    fun `двое заводят бронь на одну упаковку одновременно`() {
        val alice = dbHelper.freshUser("race-book-a")
        val bob = dbHelper.freshUser("race-book-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)

        val outcome = race(
            { sync ->
                val read = drugService.get(drug.id, alice.id)
                val claims = reservationService.snapshotOn(read, alice.id)
                sync()
                placement.place(read.id, alice.id, BigDecimal("5"), claims.version)
            },
            { sync ->
                val read = drugService.get(drug.id, bob.id)
                val claims = reservationService.snapshotOn(read, bob.id)
                sync()
                placement.place(read.id, bob.id, BigDecimal("7"), claims.version)
            }
        )

        outcome.assertOneLost()
        val claimed = dbHelper.reservedOnDrug(drug.id)
        assertTrue(
            claimed.compareTo(BigDecimal("5")) == 0 || claimed.compareTo(BigDecimal("7")) == 0,
            "заявлено ровно то, что успел победивший: $claimed"
        )
    }

    /**
     * Бронь не может остаться без доступа даже на гонке.
     *
     * Проверяется и связность итога, и то, чем отвечает проигравший. Раньше он мог получить
     * нарушение внешнего ключа, то есть пятисотку на штатный конкурентный запрос; теперь
     * заведение держит корень совместимо, и выход либо ждёт его, либо отвечает ему отказом.
     */
    @Test
    fun `заведение брони против потери доступа`() {
        val alice = dbHelper.freshUser("race-access-a")
        val bob = dbHelper.freshUser("race-access-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)

        val outcome = race(
            { sync ->
                val read = drugService.get(drug.id, bob.id)
                val claims = reservationService.snapshotOn(read, bob.id)
                sync()
                placement.place(read.id, bob.id, BigDecimal("4"), claims.version)
            },
            { sync ->
                sync()
                medKitApplicationService.leave(kit.id, bob.id)
            }
        )

        outcome.assertNoDatabaseError()
        assertTrue(
            outcome.failures.all { it is DomainRuleViolated },
            "проигравший обязан получить доменный отказ: ${outcome.failures}"
        )

        val stillMember = dbHelper.isMember(kit.id, bob.id)
        val hasReservation = dbHelper.userReservation(bob.id, drug.id) != null
        assertTrue(
            stillMember || !hasReservation,
            "бронь без доступа: членства нет, а бронь осталась"
        )
        assertEquals(
            0,
            dbHelper.storedReservationsTotal(drug.id).compareTo(dbHelper.reservedOnDrug(drug.id)),
            "гонка не должна рассогласовать сохранённую сумму"
        )
    }

    @Test
    fun `правка брони против выхода сохраняет целую картину`() {
        val alice = dbHelper.freshUser("race-change-leave-a")
        val bob = dbHelper.freshUser("race-change-leave-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)
        dbHelper.reserve(bob.id, drug.id, BigDecimal("4"))
        val stated = dbHelper.storedReservationsVersion(drug.id)

        val outcome = race(
            { sync -> sync(); reservationService.changeTo(bob.id, drug.id, BigDecimal("6"), stated) },
            { sync -> sync(); medKitApplicationService.leave(kit.id, bob.id) }
        )

        outcome.assertNoDatabaseError()
        assertTrue(outcome.failures.all { it is DomainRuleViolated }, "допустим только доменный отказ")
        assertNull(dbHelper.userReservation(bob.id, drug.id))
        assertEquals(
            0,
            dbHelper.storedReservationsTotal(drug.id).compareTo(dbHelper.reservedOnDrug(drug.id)),
            "правка и выход не должны расходиться"
        )
    }

    @Test
    fun `отмена брони против выхода сохраняет целую картину`() {
        val alice = dbHelper.freshUser("race-cancel-leave-a")
        val bob = dbHelper.freshUser("race-cancel-leave-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)
        dbHelper.reserve(bob.id, drug.id, BigDecimal("4"))
        val stated = dbHelper.storedReservationsVersion(drug.id)

        val outcome = race(
            { sync -> sync(); reservationService.cancel(bob.id, drug.id, stated) },
            { sync -> sync(); medKitApplicationService.leave(kit.id, bob.id) }
        )

        outcome.assertNoDatabaseError()
        assertTrue(outcome.failures.all { it is DomainRuleViolated }, "допустим только доменный отказ")
        assertNull(dbHelper.userReservation(bob.id, drug.id))
        assertEquals(
            0,
            dbHelper.storedReservationsTotal(drug.id).compareTo(dbHelper.reservedOnDrug(drug.id)),
            "отмена и выход не должны расходиться"
        )
    }

    /**
     * Бронь против переезда той же упаковки.
     *
     * Между чтением упаковки и записью брони она может уехать в другую аптечку. Записать бронь
     * с прежней аптечкой значило бы нарушить составной ключ; заведение перечитывает упаковку
     * под блокировкой и отвечает конфликтом состояния.
     */
    @Test
    fun `заведение брони против переезда упаковки`() {
        val alice = dbHelper.freshUser("race-book-move-a")
        val bob = dbHelper.freshUser("race-book-move-b")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(source.id, quantity = 30.0)
        val read = TransactionTemplate(transactionManager).execute { drugService.get(drug.id, bob.id) }
        val stated = TransactionTemplate(transactionManager).execute {
            reservationService.snapshotOn(read, bob.id).version
        }

        val moved = CountDownLatch(1)
        val allowMoveCommit = CountDownLatch(1)
        val placementBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val relocation = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        drugApplicationService.moveToMedKit(drug.id, target.id, drug.version, alice.id)
                        moved.countDown()
                        assertTrue(allowMoveCommit.await(10, TimeUnit.SECONDS), "бронь не дошла до ожидания корня")
                    }
                }.exceptionOrNull()
            }
            assertTrue(moved.await(10, TimeUnit.SECONDS), "переезд не изменил упаковку")

            val reservation = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        placementBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        placement.place(read.id, bob.id, BigDecimal("3"), stated)
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(placementBackend.poll(10, TimeUnit.SECONDS) ?: error("бронь не начала транзакцию"))
            allowMoveCommit.countDown()

            assertNull(relocation.get(30, TimeUnit.SECONDS)?.rootCause(), "переезд обязан завершиться")
            assertTrue(
                reservation.get(30, TimeUnit.SECONDS)?.rootCause() is StaleVersion,
                "прочитанная до переезда упаковка обязана устареть"
            )
        } finally {
            allowMoveCommit.countDown()
            pool.shutdownNow()
        }

        assertEquals(target.id, dbHelper.requireDrug(drug.id).medKitId)
        assertNull(dbHelper.userReservation(bob.id, drug.id))
    }

    /**
     * Изменение брони против переезда её упаковки.
     *
     * Упаковка уезжает в аптечку, которой участник не видит, поэтому его бронь снимается. Правка
     * не должна ни записать в исчезнувшую строку, ни уйти под прежним `med_kit_id`: команда
     * перечитывает упаковку под удерживаемым корнем и отвечает конфликтом состояния.
     */
    @Test
    fun `изменение брони против переноса упаковки`() {
        val alice = dbHelper.freshUser("race-change-move-a")
        val bob = dbHelper.freshUser("race-change-move-b")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(source.id, quantity = 30.0)
        dbHelper.reserve(bob.id, drug.id, BigDecimal("5"))

        val outcome = race(
            { sync ->
                val claims = dbHelper.storedReservationsVersion(drug.id)
                sync()
                changing.changeTo(bob.id, drug.id, BigDecimal("7"), claims)
            },
            { sync ->
                sync()
                drugApplicationService.moveToMedKit(drug.id, target.id, drug.version, alice.id)
            }
        )

        outcome.assertNoDatabaseError()
        assertTrue(
            outcome.failures.all { it is DomainRuleViolated },
            "проигравший обязан получить доменный отказ, а не нарушение ключа: ${outcome.failures}"
        )
        assertEquals(
            0,
            dbHelper.storedReservationsTotal(drug.id).compareTo(dbHelper.reservedOnDrug(drug.id)),
            "сохранённая сумма обязана сойтись со строками в любом порядке"
        )
    }

    /** Заведение упаковки против глобального удаления аптечки: отказ, а не нарушение ключа. */
    @Test
    fun `заведение упаковки против удаления аптечки`() {
        val alice = dbHelper.freshUser("race-place-delete")
        val kit = dbHelper.freshMedKit(alice.id)

        val outcome = race(
            { sync ->
                sync()
                drugApplicationService.createInMedKit(kit.id, drugRequest(), alice.id)
            },
            { sync ->
                sync()
                medKitApplicationService.delete(kit.id, alice.id)
            }
        )

        outcome.assertNoDatabaseError()
        assertTrue(
            outcome.failures.all { it is NotAMember },
            "допустим только отказ исчезнувшего ресурса: ${outcome.failures}"
        )
        assertNull(dbHelper.medKit(kit.id), "аптечка обязана исчезнуть в любом порядке")
    }

    /**
     * Новая бронь внутри sync обязана взять корень до того, как списание заблокирует упаковку.
     * Иначе delete держит корень и ждёт упаковку, а sync держит упаковку и ждёт корень.
     */
    @Test
    fun `синхронизация с новой бронью не образует deadlock с удалением аптечки`() {
        val alice = dbHelper.freshUser("race-sync-delete")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)

        val rootTaken = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val syncBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val deletion = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        medKitAccess.holdLifecycleAccess(setOf(kit.id), alice.id)
                        rootTaken.countDown()
                        assertTrue(allowDelete.await(10, TimeUnit.SECONDS), "sync не дошёл до ожидания корня")
                        medKitService.deleteRoot(kit.id)
                    }
                }.exceptionOrNull()
            }
            assertTrue(rootTaken.await(10, TimeUnit.SECONDS), "удаление не удержало корень")

            val synchronisation = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        syncBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        drugApplicationService.synchronise(
                            drug.id,
                            Uuid.random(),
                            DrugSyncRequest(
                                consumed = BigDecimal.ONE,
                                drugVersion = drug.version,
                                reservation = ReservationSyncRequest(BigDecimal("2"))
                            ),
                            alice.id
                        )
                    }
                }.exceptionOrNull()
            }

            awaitDatabaseLock(syncBackend.poll(10, TimeUnit.SECONDS) ?: error("sync не начал транзакцию"))
            allowDelete.countDown()

            assertNull(deletion.get(30, TimeUnit.SECONDS)?.rootCause(), "удаление обязано завершиться")
            val syncFailure = synchronisation.get(30, TimeUnit.SECONDS)?.rootCause()
            assertTrue(syncFailure is NotAMember, "sync проигрывает доменным отказом: $syncFailure")
        } finally {
            allowDelete.countDown()
            pool.shutdownNow()
        }

        assertNull(dbHelper.medKit(kit.id))
        assertNull(dbHelper.drug(drug.id))
    }

    /**
     * Переезд против выхода из целевой аптечки — обратный порядок.
     *
     * Симметричен проверке ниже: там первым корень берёт выход, здесь — переезд. Выход обязан
     * дождаться переезда, а уцелевшая бронь Боба — уйти вместе с его членством.
     */
    @Test
    fun `выход из целевой аптечки ждёт одиночный перенос`() {
        val alice = dbHelper.freshUser("race-move-first-a")
        val bob = dbHelper.freshUser("race-move-first-b")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        dbHelper.join(target.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(source.id, quantity = 20.0)
        dbHelper.reserve(bob.id, drug.id, BigDecimal("5"))

        val outcome = race(
            { sync ->
                sync()
                drugApplicationService.moveToMedKit(drug.id, target.id, drug.version, alice.id)
            },
            { sync ->
                sync()
                medKitApplicationService.leave(target.id, bob.id)
            }
        )

        outcome.assertNoDatabaseError()
        assertTrue(outcome.failures.isEmpty(), "обе команды законны в любом порядке: ${outcome.failures}")
        assertEquals(target.id, dbHelper.requireDrug(drug.id).medKitId)
        assertNull(dbHelper.userReservation(bob.id, drug.id), "бронь не переживает выход из целевой аптечки")
        assertEquals(
            0, dbHelper.storedReservationsTotal(drug.id).compareTo(dbHelper.reservedOnDrug(drug.id)),
            "сохранённая сумма обязана сойтись со строками"
        )
    }

    /**
     * Правило «одна бронь на пару» держит чтение, а на гонке — ключ.
     *
     * Оба чтения проходят: брони ещё нет ни для одного. Дальше срабатывает первичный ключ, и
     * важно, что наружу летит доменный отказ, а не нарушение ограничения: осмысленный запрос
     * не должен отвечать пятисоткой.
     */
    @Test
    fun `один человек заводит одну бронь дважды одновременно`() {
        val owner = dbHelper.freshUser("race-dup")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)

        val outcome = race(
            { sync ->
                val read = drugService.get(drug.id, owner.id)
                val claims = reservationService.snapshotOn(read, owner.id)
                sync()
                placement.place(read.id, owner.id, BigDecimal("5"), claims.version)
            },
            { sync ->
                val read = drugService.get(drug.id, owner.id)
                val claims = reservationService.snapshotOn(read, owner.id)
                sync()
                placement.place(read.id, owner.id, BigDecimal("6"), claims.version)
            }
        )

        assertEquals(1, outcome.failures.size, "ровно одна сторона обязана проиграть: ${outcome.failures}")
        assertTrue(
            outcome.failures.single() is DomainRuleViolated,
            "проигравший получает доменный отказ, а не нарушение ограничения: ${outcome.failures.single()}"
        )
    }

    /** То же для вступления: дважды в одну аптечку не вступают. */
    @Test
    fun `двое вступают по одному приглашению одновременно`() {
        val alice = dbHelper.freshUser("race-join-a")
        val bob = dbHelper.freshUser("race-join-b")
        val kit = dbHelper.freshMedKit(alice.id)
        val key = TransactionTemplate(transactionManager).execute { inviting.invite(kit.id, alice.id) }

        val outcome = race(
            { sync ->
                sync()
                joining.joinByInvitation(key, bob.id)
            },
            { sync ->
                sync()
                joining.joinByInvitation(key, bob.id)
            }
        )

        assertEquals(1, outcome.failures.size, "второе вступление обязано быть отвергнуто: ${outcome.failures}")
        assertTrue(
            outcome.failures.single() is AlreadyMember,
            "повтор отвергается как AlreadyMember: ${outcome.failures.single()}"
        )
    }

    @Test
    fun `разные люди вступают по одному приглашению одновременно`() {
        val alice = dbHelper.freshUser("race-join-many-a")
        val bob = dbHelper.freshUser("race-join-many-b")
        val charlie = dbHelper.freshUser("race-join-many-c")
        val kit = dbHelper.freshMedKit(alice.id)
        val key = TransactionTemplate(transactionManager).execute { inviting.invite(kit.id, alice.id) }

        val outcome = race(
            { sync -> sync(); joining.joinByInvitation(key, bob.id) },
            { sync -> sync(); joining.joinByInvitation(key, charlie.id) }
        )

        assertTrue(outcome.failures.isEmpty(), "разные membership не конфликтуют: ${outcome.failures}")
        assertEquals(3, dbHelper.medKit(kit.id)?.userCount)
        assertTrue(dbHelper.isMember(kit.id, bob.id))
        assertTrue(dbHelper.isMember(kit.id, charlie.id))
    }

    @Test
    fun `последний выход против вступления оставляет только целостный результат`() {
        val alice = dbHelper.freshUser("race-last-leave-a")
        val bob = dbHelper.freshUser("race-last-leave-b")
        val kit = dbHelper.freshMedKit(alice.id)
        val key = TransactionTemplate(transactionManager).execute { inviting.invite(kit.id, alice.id) }

        val outcome = race(
            { sync -> sync(); medKitApplicationService.leave(kit.id, alice.id) },
            { sync -> sync(); joining.joinByInvitation(key, bob.id) }
        )

        val stored = dbHelper.medKit(kit.id)
        if (stored == null) {
            assertEquals(1, outcome.failures.size)
            assertTrue(outcome.failures.single() is NotAMember)
        } else {
            assertTrue(outcome.failures.isEmpty(), "вступивший остаётся единственным участником: ${outcome.failures}")
            assertEquals(1, stored.userCount)
            assertTrue(dbHelper.isMember(kit.id, bob.id))
        }
    }

    @Test
    fun `глобальное удаление против вступления не оставляет membership без аптечки`() {
        val alice = dbHelper.freshUser("race-delete-join-a")
        val bob = dbHelper.freshUser("race-delete-join-b")
        val kit = dbHelper.freshMedKit(alice.id)
        val key = TransactionTemplate(transactionManager).execute { inviting.invite(kit.id, alice.id) }

        val outcome = race(
            { sync -> sync(); medKitApplicationService.delete(kit.id, alice.id) },
            { sync -> sync(); joining.joinByInvitation(key, bob.id) }
        )

        assertTrue(outcome.failures.all { it is NotAMember }, "допустим только отказ исчезнувшего ресурса")
        assertNull(dbHelper.medKit(kit.id))
        assertTrue(!dbHelper.isMember(kit.id, bob.id))
    }

    @Test
    fun `глобальное удаление против выхода не оставляет пустой корень`() {
        val alice = dbHelper.freshUser("race-delete-leave-a")
        val bob = dbHelper.freshUser("race-delete-leave-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)

        val outcome = race(
            { sync -> sync(); medKitApplicationService.delete(kit.id, alice.id) },
            { sync -> sync(); medKitApplicationService.leave(kit.id, bob.id) }
        )

        assertTrue(outcome.failures.all { it is NotAMember }, "допустим только отказ исчезнувшего ресурса")
        assertNull(dbHelper.medKit(kit.id))
    }

    // ── Оснастка ──────────────────────────────────────────────────────────────────────

    /**
     * Обе стороны читают, дожидаются друг друга и только потом пишут.
     *
     * Барьер вызывается **изнутри** сценария, между чтением и записью. Поставить его перед всем
     * действием мало: тогда быстрая сторона успевает прочитать и записать целиком, пока
     * медленная только читает, — гонки не выходит, и тест зеленеет по случайности.
     */
    private fun race(vararg sides: (sync: () -> Unit) -> Any?): Outcome {
        val barrier = CyclicBarrier(sides.size)
        val pool = Executors.newFixedThreadPool(sides.size)
        try {
            val tasks = sides.map { side ->
                pool.submit<Throwable?> {
                    runCatching {
                        TransactionTemplate(transactionManager).execute {
                            side { barrier.await(10, TimeUnit.SECONDS) }
                        }
                    }.exceptionOrNull()
                }
            }
            return Outcome(tasks.map { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun drugRequest() = DrugCreateRequest(Uuid.random(), name = "Race_${Uuid.random()}",
        quantity = BigDecimal("10"),
        quantityUnitId = dbHelper.unit().id
    )

    /** Ждёт именно блокировку в PostgreSQL; задержка потока не считается доказательством гонки. */
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
        error("транзакция переноса не дождалась блокировки в PostgreSQL")
    }

    private class Outcome(results: List<Throwable?>) {
        val failures = results.filterNotNull().map { it.rootCause() }

        /**
         * Штатная гонка отвечает доменным отказом, а не поломкой схемы.
         *
         * Нарушение внешнего ключа наружу — это пятисотка на осмысленный запрос: клиенту нечего
         * с ней делать, а причина в том, что команды разошлись, а не в том, что он неправ.
         */
        fun assertNoDatabaseError() {
            val fromDatabase = failures.filterIsInstance<SQLException>()
            assertTrue(fromDatabase.isEmpty(), "штатная гонка упёрлась в ошибку БД: $fromDatabase")
        }

        fun assertOneLost() {
            assertEquals(1, failures.size, "ровно одна сторона обязана проиграть: $failures")
            assertTrue(failures.single() is StaleVersion, "проигравший отвергается по версии: ${failures.single()}")
        }
    }

}

private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
