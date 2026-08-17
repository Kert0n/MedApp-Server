package org.kert0n.medappserver.domain

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty

/**
 * Инварианты агрегата.
 *
 * Ни Spring, ни базы, ни единой сущности: домен — неизменяемые значения, а участники здесь
 * всего лишь идентификаторы. Тем же правилам, но уже вместе с записью в базу, посвящены
 * `DrugServiceTest` и `PlanReconciliationTest`.
 */
class DrugAggregateTest {

    private val kit = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    private fun drug(quantity: Double): Drug =
        Drug(medKitId = kit, name = "Aspirin", quantity = qty(quantity), quantityUnit = "pills")

    // ── Создание ─────────────────────────────────────────────────────────────────

    @Test
    fun `препарат не заводится с нулевым количеством`() {
        assertFailsWith<InvalidQuantity> { drug(0.0) }
    }

    @Test
    fun `препарат не заводится с отрицательным количеством`() {
        assertFailsWith<InvalidQuantity> { drug(-1.0) }
    }

    // ── Остаток ──────────────────────────────────────────────────────────────────

    @Test
    fun `количество меняется в обе стороны`() {
        assertQty(25.0, drug(10.0).changeQuantityTo(qty(25.0)).quantity)
        assertQty(4.0, drug(10.0).changeQuantityTo(qty(4.0)).quantity)
        assertFailsWith<InvalidQuantity> { drug(10.0).changeQuantityTo(qty(0.0)) }
    }

    /**
     * Пересчёт учёта вниз — та же ветка, что и списание: агрегат загружен целиком, поэтому
     * планы сжимаются по всем сразу, а не по тому, о котором вспомнили.
     */
    @Test
    fun `уменьшение количества сжимает планы`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(60.0))
            .createPlan(bob, qty(40.0))

        val corrected = drug.changeQuantityTo(qty(50.0))

        assertQty(30.0, corrected.planOf(alice)!!.plannedAmount)
        assertQty(20.0, corrected.planOf(bob)!!.plannedAmount)
        assertQty(50.0, corrected.plannedTotal)
    }

    @Test
    fun `увеличение количества планы не трогает`() {
        val drug = drug(100.0).createPlan(alice, qty(60.0))

        assertQty(60.0, drug.changeQuantityTo(qty(200.0)).planOf(alice)!!.plannedAmount)
    }

    @Test
    fun `списать больше остатка нельзя`() {
        val drug = drug(10.0)

        assertFailsWith<InsufficientStock> { drug.consume(qty(10.000001)) }
        assertQty(10.0, drug.quantity)
    }

    @Test
    fun `списание нуля отвергается`() {
        assertFailsWith<InvalidQuantity> { drug(10.0).consume(qty(0.0)) }
    }

    @Test
    fun `списание всего остатка исчерпывает препарат`() {
        val drug = drug(10.0).createPlan(alice, qty(4.0))

        // Препарата с нулевым остатком не бывает, поэтому такого состояния агрегат не
        // строит: он сообщает, что препарат кончился, а строку удаляет вызывающий.
        assertNull(drug.consume(qty(10.0)), "препарат кончился")
    }

    @Test
    fun `препарат с нулевым остатком не собирается вовсе`() {
        assertFailsWith<InvalidQuantity> {
            Drug(medKitId = kit, name = "Aspirin", quantity = qty(0.0), quantityUnit = "pills")
        }
    }

    // ── Планы ────────────────────────────────────────────────────────────────────

    @Test
    fun `второй план того же пользователя отвергается`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))

        assertFailsWith<TreatmentPlanAlreadyExists> { drug.createPlan(alice, qty(5.0)) }
        assertEquals(1, drug.plans.size)
    }

    @Test
    fun `сумма планов не может превысить остаток`() {
        val drug = drug(100.0).createPlan(alice, qty(70.0))

        assertFailsWith<PlannedAmountExceedsStock> { drug.createPlan(bob, qty(31.0)) }
        // Ровно остаток — можно: запрет на «больше», а не на «всё».
        val full = drug.createPlan(bob, qty(30.0))
        assertQty(100.0, full.plannedTotal)

        // Свободного не осталось: даже наименьшее возможное количество не резервируется.
        val carol = UUID.randomUUID()
        assertFailsWith<PlannedAmountExceedsStock> { full.createPlan(carol, qty("0.000001")) }
    }

    @Test
    fun `изменение плана не считает его прежний размер занятым`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(50.0))
            .createPlan(bob, qty(30.0))

        val changed = drug.changePlan(bob, qty(50.0))
        assertQty(50.0, changed.planOf(bob)!!.plannedAmount)

        assertFailsWith<PlannedAmountExceedsStock> { changed.changePlan(bob, qty(51.0)) }
    }

    @Test
    fun `отмена несуществующего плана — ошибка, отзыв — нет`() {
        val drug = drug(100.0)

        assertFailsWith<NoSuchTreatmentPlan> { drug.cancelPlan(alice) }
        assertEquals(drug, drug.revokePlanOf(alice))
    }

    // ── Приём ────────────────────────────────────────────────────────────────────

    @Test
    fun `приём уменьшает и план, и остаток`() {
        val drug = drug(100.0).createPlan(alice, qty(30.0))

        val outcome = drug.applyIntake(alice, qty(10.0))

        assertQty(20.0, outcome.plan!!.plannedAmount)
        assertQty(90.0, outcome.drug!!.quantity)
    }

    @Test
    fun `приём больше собственного плана отвергается`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))

        assertFailsWith<IntakeExceedsPlan> { drug.applyIntake(alice, qty(11.0)) }
        assertQty(100.0, drug.quantity)
    }

    /**
     * Состояние, где план больше остатка, через команды не собрать — его создаёт чужое
     * списание, увиденное этой транзакцией уже после того, как план был прочитан. Домен
     * восстанавливается из хранилища как есть, поэтому проверить такой случай можно только
     * здесь.
     */
    @Test
    fun `приём больше остатка отвергается, даже если план позволяет`() {
        val staleId = UUID.randomUUID()
        val stale = Drug(
            id = staleId, medKitId = kit, name = "Aspirin", quantity = qty(2.0),
            quantityUnit = "pills", plans = listOf(TreatmentPlan(alice, staleId, qty(10.0)))
        )

        assertFailsWith<InsufficientStock> { stale.applyIntake(alice, qty(5.0)) }
    }

    @Test
    fun `приём без плана отвергается`() {
        assertFailsWith<NoSuchTreatmentPlan> { drug(100.0).applyIntake(alice, qty(1.0)) }
    }

    @Test
    fun `приём, исчерпавший план, удаляет его и оставляет препарат`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice, qty(10.0))

        assertNull(outcome.plan, "исчерпанный план не возвращается")
        assertNull(outcome.drug!!.planOf(alice))
        assertQty(90.0, outcome.drug!!.quantity)
    }

    @Test
    fun `приём, исчерпавший препарат, уносит все планы`() {
        val drug = drug(10.0).createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice, qty(10.0))

        assertNull(outcome.drug, "препарат кончился этим приёмом")
        assertNull(outcome.plan)
    }

    // ── Пересчёт планов ──────────────────────────────────────────────────────────

    @Test
    fun `списание сжимает планы пропорционально`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(60.0))
            .createPlan(bob, qty(40.0))

        val left = drug.consume(qty(50.0))!!

        assertQty(30.0, left.planOf(alice)!!.plannedAmount)
        assertQty(20.0, left.planOf(bob)!!.plannedAmount)
        assertQty(50.0, left.plannedTotal)
    }

    @Test
    fun `планы, укладывающиеся в остаток, не трогаются`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(20.0))
            .createPlan(bob, qty(20.0))

        val left = drug.consume(qty(50.0))!!

        assertQty(20.0, left.planOf(alice)!!.plannedAmount)
        assertQty(20.0, left.planOf(bob)!!.plannedAmount)
    }

    /**
     * Коэффициент 60/90 — бесконечная дробь, и отдельным числом его не выразить. Умножение
     * до деления оставляет 30 → 20 точным; сумма сжатых планов не должна превысить остаток.
     */
    @Test
    fun `бесконечный коэффициент не создаёт количества из воздуха`() {
        val drug = drug(90.0)
            .createPlan(alice, qty(30.0))
            .createPlan(bob, qty(60.0))

        val left = drug.consume(qty(30.0))!!

        assertQty(20.0, left.planOf(alice)!!.plannedAmount)
        assertQty(40.0, left.planOf(bob)!!.plannedAmount)
        assertTrue(left.plannedTotal <= left.quantity, "сумма планов не превышает остаток")
    }

    // ── Сравнение ────────────────────────────────────────────────────────────────

    /**
     * Препарат — сущность: списание меняет его состояние, но не делает другим препаратом.
     * На этом же держится будущая версионность — версия не должна влиять на сравнение.
     */
    @Test
    fun `два состояния одного препарата равны`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))
        val consumed = drug.consume(qty(30.0))!!

        assertEquals(drug, consumed)
        assertEquals(drug.hashCode(), consumed.hashCode())
        assertNotEquals(drug, drug(100.0), "разные препараты остаются разными")
    }

    // ── Переезд ──────────────────────────────────────────────────────────────────

    @Test
    fun `переезд уносит планы тех, кто целевую аптечку не видит`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(30.0))
            .createPlan(bob, qty(30.0))
        val target = UUID.randomUUID()

        val moved = drug.moveTo(target, setOf(alice))

        assertEquals(target, moved.medKitId)
        assertQty(30.0, moved.planOf(alice)!!.plannedAmount)
        assertNull(moved.planOf(bob), "план без доступа не переезжает")
    }
}
