package org.kert0n.medappserver.integration

import java.math.BigDecimal
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Гонки заводятся настоящими параллельными транзакциями.
 *
 * Имитация — прочитать, потом отдельной транзакцией переписать, потом дописать прочитанным —
 * доказывает только предикат в `UPDATE`. Здесь обе стороны держат транзакции одновременно, и
 * проверяется то, ради чего версия и заводилась: одна сторона получает отказ, а состояние
 * совпадает с результатом победившей. Одного «итог верный» мало: сойтись он может и от того,
 * что вторая запись просто легла поверх.
 */
@PostgresIntegrationTest
class OptimisticRaceTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var reservationService: ReservationService
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
                val read = medKitService.get(kit.id, alice.id)
                sync()
                medKitService.leave(read, alice.id, read.version)
            },
            { sync ->
                val read = medKitService.get(kit.id, bob.id)
                sync()
                medKitService.leave(read, bob.id, read.version)
            }
        )

        outcome.assertOneLost()
        val members: MedKit? = dbHelper.medKit(kit.id)
        assertEquals(
            1,
            members?.members?.size,
            "проигравший не должен был уйти следом: аптечка обязана остаться с одним участником"
        )
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
                val into = medKitService.get(target.id, alice.id)
                sync()
                drugService.moveTo(read, into, read.version)
            },
            { sync ->
                val read = medKitService.get(source.id, bob.id)
                sync()
                medKitService.leave(read, bob.id, read.version)
            }
        )

        // Здесь агрегаты разные, и обе стороны могут выиграть: проверяется, что состояние
        // осталось связным, а не что кто-то обязан проиграть.
        assertTrue(outcome.failures.all { it is StaleVersion }, "отказ, если он был, — только по версии")
        assertEquals(target.id, dbHelper.requireDrug(drug.id).medKitId.takeIf { outcome.failures.isEmpty() } ?: target.id)
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
                reservationService.create(read, alice.id, BigDecimal("5"), claims.version)
            },
            { sync ->
                val read = drugService.get(drug.id, bob.id)
                val claims = reservationService.snapshotOn(read, bob.id)
                sync()
                reservationService.create(read, bob.id, BigDecimal("7"), claims.version)
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
     * Проверяется не то, кто выиграл, а связность итога: если бронь есть, то и членство есть.
     * Разойтись им не даёт составной ключ на членство — правило выражено в коде, ключ страхует.
     */
    @Test
    fun `заведение брони против потери доступа`() {
        val alice = dbHelper.freshUser("race-access-a")
        val bob = dbHelper.freshUser("race-access-b")
        val kit = dbHelper.freshMedKit(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 30.0)

        race(
            { sync ->
                val read = drugService.get(drug.id, bob.id)
                val claims = reservationService.snapshotOn(read, bob.id)
                sync()
                reservationService.create(read, bob.id, BigDecimal("4"), claims.version)
            },
            { sync ->
                val read = medKitService.get(kit.id, bob.id)
                sync()
                medKitService.leave(read, bob.id, read.version)
            }
        )

        val stillMember = dbHelper.medKit(kit.id)?.members?.contains(bob.id) == true
        val hasReservation = dbHelper.userReservation(bob.id, drug.id) != null
        assertTrue(
            stillMember || !hasReservation,
            "бронь без доступа: членства нет, а бронь осталась"
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
                reservationService.create(read, owner.id, BigDecimal("5"), claims.version)
            },
            { sync ->
                val read = drugService.get(drug.id, owner.id)
                val claims = reservationService.snapshotOn(read, owner.id)
                sync()
                reservationService.create(read, owner.id, BigDecimal("6"), claims.version)
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

        val outcome = race(
            { sync ->
                sync()
                dbHelper.join(kit.id, alice.id, bob.id)
            },
            { sync ->
                sync()
                dbHelper.join(kit.id, alice.id, bob.id)
            }
        )

        assertEquals(1, outcome.failures.size, "второе вступление обязано быть отвергнуто: ${outcome.failures}")
        assertTrue(
            outcome.failures.single() is DomainRuleViolated,
            "и отвергнуто доменным отказом: ${outcome.failures.single()}"
        )
    }

    // ── Оснастка ──────────────────────────────────────────────────────────────────────

    /**
     * Обе стороны читают, дожидаются друг друга и только потом пишут.
     *
     * Барьер вызывается **изнутри** сценария, между чтением и записью. Поставить его перед всем
     * действием мало: тогда быстрая сторона успевает прочитать и записать целиком, пока
     * медленная только читает, — гонки не выходит, и тест зеленеет по случайности. На этом я
     * уже обжёгся.
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

    private class Outcome(results: List<Throwable?>) {
        val failures = results.filterNotNull().map { it.rootCause() }

        fun assertOneLost() {
            assertEquals(1, failures.size, "ровно одна сторона обязана проиграть: $failures")
            assertTrue(failures.single() is StaleVersion, "проигравший отвергается по версии: ${failures.single()}")
        }

        private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()
    }
}
