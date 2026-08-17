package org.kert0n.medappserver.integration

import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.createPlanLatest
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Гонки: что происходит, когда две команды приходят одновременно.
 *
 * Транзакции на классе нет и быть не может — проверяется как раз то, что видят друг о друге
 * разные транзакции. Каждый поток открывает свою и синхронизируется барьером ровно между
 * чтением и записью: без барьера тест проходил бы и на сломанной блокировке, просто потому
 * что вторая команда успевала прочитать уже изменённое состояние.
 *
 * Проверяется число фактических изменений, а не итоговое состояние. Разница существенная: при
 * потерянных обновлениях восемь параллельных списаний оставляют ровно тот же остаток, что
 * одно, и по остатку ошибку не отличить.
 */
@PostgresIntegrationTest
class ConcurrencyTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugs: DrugStore
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val tx: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    /**
     * Запускает действия одновременно и говорит, сколько из них прошло.
     *
     * Барьер передаётся внутрь: место синхронизации выбирает сам сценарий, и это всегда точка
     * между «прочитал состояние» и «записал решение».
     */
    private fun raceOf(vararg actions: (CyclicBarrier) -> Unit): Int {
        val barrier = CyclicBarrier(actions.size)
        val pool = Executors.newFixedThreadPool(actions.size)
        try {
            val results = actions.map { action -> pool.submit<Boolean> { runCatching { action(barrier) }.isSuccess } }
            pool.shutdown()
            check(pool.awaitTermination(30, TimeUnit.SECONDS)) { "гонка не завершилась за отведённое время" }
            return results.count { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    // ── Препарат ─────────────────────────────────────────────────────────────────

    @Test
    fun `два одновременных списания не теряют друг друга`() {
        val alice = tx.execute { dbHelper.freshUser("race") }!!
        val kit = tx.execute { medKitService.create(alice.id) }!!
        val drug = tx.execute { dbHelper.freshDrug(kit.id, 100.0) }!!

        fun consumeOne(barrier: CyclicBarrier) {
            tx.execute {
                val loaded = drugs.findAccessible(drug.id, alice.id)!!
                barrier.await()
                drugs.save(loaded.consume(Quantity(qty(1.0), loaded.quantity.unit))!!)
            }
        }

        val succeeded = raceOf(::consumeOne, ::consumeOne)

        assertEquals(1, succeeded, "обе команды решали по одному и тому же состоянию — пройти должна одна")
        assertEquals(qty(99.0), dbHelper.drugQuantity(drug.id), "списание засчитано ровно одно")
        assertEquals(1L, dbHelper.requireDrug(drug.id).version, "версия выросла ровно на один успех")
    }

    /**
     * Восемь приёмов сразу.
     *
     * Считается не остаток, а число успехов, и уже потом проверяется, что остаток им ровно
     * соответствует. Именно этой проверки не хватало бы, если смотреть только на итог.
     */
    @Test
    fun `параллельные приёмы списывают ровно столько раз, сколько прошло`() {
        val alice = tx.execute { dbHelper.freshUser("intake-race") }!!
        val kit = tx.execute { medKitService.create(alice.id) }!!
        val drug = tx.execute { dbHelper.freshDrug(kit.id, 100.0) }!!
        tx.execute { drugService.createPlanLatest(alice.id, drug.id, qty(50.0)) }

        fun intake(barrier: CyclicBarrier) {
            tx.execute {
                val version = drugService.require(drug.id, alice.id).version
                barrier.await()
                drugService.recordIntake(alice.id, drug.id, qty(1.0), version)
            }
        }

        val actions = Array(8) { { barrier: CyclicBarrier -> intake(barrier) } }
        val succeeded = raceOf(*actions)

        assertTrue(succeeded >= 1, "хотя бы один приём обязан пройти")
        assertEquals(qty(100.0 - succeeded), dbHelper.drugQuantity(drug.id), "списаний столько же, сколько успехов")
        assertEquals(qty(50.0 - succeeded), dbHelper.userPlan(alice.id, drug.id), "план уменьшен на столько же")
    }

    // ── Аптечка ──────────────────────────────────────────────────────────────────

    /**
     * Тот самый сценарий, ради которого версия у аптечки и появилась.
     *
     * При `READ COMMITTED` оба последних участника видят чужую строку членства живой, оба
     * решают «я не последний» — и без версии оба выходят, оставляя аптечку без людей, но с
     * препаратами.
     */
    @Test
    fun `двое последних участников не могут выйти одновременно`() {
        val alice = tx.execute { dbHelper.freshUser("last-a") }!!
        val bob = tx.execute { dbHelper.freshUser("last-b") }!!
        val kit = tx.execute { medKitService.create(alice.id) }!!
        tx.execute { medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id) }
        val drug = tx.execute { dbHelper.freshDrug(kit.id, 10.0) }!!

        fun leave(userId: UUID): (CyclicBarrier) -> Unit = { barrier ->
            tx.execute {
                val version = medKitService.requireById(kit.id).version
                barrier.await()
                orchestrator.leaveMedKit(kit.id, userId, version)
            }
        }

        val succeeded = raceOf(leave(alice.id), leave(bob.id))

        assertEquals(1, succeeded, "второй выход обязан упереться в версию")

        val left = medKitService.findById(kit.id)
        assertNotNull(left, "аптечка не должна исчезнуть: в ней остался участник")
        assertEquals(1, left.members.size)
        assertNotNull(dbHelper.drug(drug.id), "препарат остался вместе с аптечкой")
    }

    /**
     * Вступление против удаления.
     *
     * Проверяется инвариант, а не конкретный код ответа: важно, что участник не оказывается
     * внутри удалённой аптечки. Какая из двух команд выиграет — дело момента.
     */
    @Test
    fun `вступление не может завершиться внутри удалённой аптечки`() {
        val alice = tx.execute { dbHelper.freshUser("join-a") }!!
        val carol = tx.execute { dbHelper.freshUser("join-c") }!!
        val kit = tx.execute { medKitService.create(alice.id) }!!
        val key = tx.execute { medKitService.invite(kit.id, alice.id) }!!

        val join: (CyclicBarrier) -> Unit = { barrier ->
            tx.execute {
                medKitService.requireById(kit.id)
                barrier.await()
                medKitService.joinByInvitation(key, carol.id)
            }
        }
        val remove: (CyclicBarrier) -> Unit = { barrier ->
            tx.execute {
                val version = medKitService.requireById(kit.id).version
                barrier.await()
                orchestrator.delete(kit.id, alice.id, version)
            }
        }

        raceOf(join, remove)

        val survived = medKitService.findById(kit.id)
        val carolKits = medKitService.refsOfUser(carol.id).map { it.id }
        if (survived == null) {
            assertFalse(kit.id in carolKits, "участник не должен числиться в удалённой аптечке")
        } else {
            assertTrue(survived.members.contains(alice.id), "оставшаяся аптечка сохраняет прежнего участника")
        }
    }

    /**
     * Переезд препарата против выхода того, у кого на него план.
     *
     * Правило «план не переживает утрату доступа» записано дважды — в `Drug.moveTo` и в
     * массовом удалении при выходе, — и обе записи обязаны давать один и тот же итог,
     * независимо от того, какая команда успела первой.
     */
    @Test
    fun `план не переживает утрату доступа ни при каком порядке`() {
        val alice = tx.execute { dbHelper.freshUser("move-a") }!!
        val bob = tx.execute { dbHelper.freshUser("move-b") }!!
        val shared = tx.execute { medKitService.create(alice.id) }!!
        tx.execute { medKitService.joinByInvitation(medKitService.invite(shared.id, alice.id), bob.id) }
        val personal = tx.execute { medKitService.create(alice.id) }!!
        val drug = tx.execute { dbHelper.freshDrug(shared.id, 40.0) }!!
        tx.execute { drugService.createPlanLatest(bob.id, drug.id, qty(10.0)) }

        val move: (CyclicBarrier) -> Unit = { barrier ->
            tx.execute {
                val version = drugService.require(drug.id, alice.id).version
                barrier.await()
                orchestrator.moveDrug(drug.id, personal.id, alice.id, version)
            }
        }
        val leave: (CyclicBarrier) -> Unit = { barrier ->
            tx.execute {
                val version = medKitService.requireById(shared.id).version
                barrier.await()
                orchestrator.leaveMedKit(shared.id, bob.id, version)
            }
        }

        val succeeded = raceOf(move, leave)

        assertTrue(succeeded >= 1, "хотя бы одна команда обязана пройти")
        assertNull(dbHelper.userPlan(bob.id, drug.id), "план Боба не должен пережить ни переезд, ни выход")
    }
}
