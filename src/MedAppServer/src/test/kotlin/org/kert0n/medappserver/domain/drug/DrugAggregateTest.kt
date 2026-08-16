package org.kert0n.medappserver.domain.drug

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.IntakeExceedsPlan
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.NoSuchTreatmentPlan
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.error.QuantityNotIncreased
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
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
        Drug.create(medKitId = kit, name = "Aspirin", quantity = qty(quantity), quantityUnit = "pills")

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
    fun `количество можно только увеличить`() {
        val drug = drug(10.0).increaseQuantityTo(qty(25.0))
        assertQty(25.0, drug.quantity)

        assertFailsWith<QuantityNotIncreased> { drug.increaseQuantityTo(qty(24.0)) }
        assertFailsWith<QuantityNotIncreased> { drug.increaseQuantityTo(qty(25.0)) }
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
    fun `списание всего остатка исчерпывает препарат и уносит планы`() {
        val drug = drug(10.0).createPlan(alice, qty(4.0))

        val outcome = drug.consume(qty(10.0))

        assertTrue(outcome.exhausted, "препарат кончился")
        assertQty(0.0, outcome.drug.quantity)
        assertTrue(outcome.drug.plans.isEmpty(), "планы не переживают препарат")
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

        assertFalse(outcome.drugExhausted)
        assertQty(20.0, outcome.plan!!.plannedAmount)
        assertQty(90.0, outcome.drug.quantity)
    }

    @Test
    fun `приём больше собственного плана отвергается`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))

        assertFailsWith<IntakeExceedsPlan> { drug.applyIntake(alice, qty(11.0)) }
        assertQty(100.0, drug.quantity)
    }

    @Test
    fun `приём без плана отвергается`() {
        assertFailsWith<NoSuchTreatmentPlan> { drug(100.0).applyIntake(alice, qty(1.0)) }
    }

    @Test
    fun `приём, исчерпавший план, удаляет его и оставляет препарат`() {
        val drug = drug(100.0).createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice, qty(10.0))

        assertFalse(outcome.drugExhausted)
        assertNull(outcome.plan, "исчерпанный план не возвращается")
        assertNull(outcome.drug.planOf(alice))
        assertQty(90.0, outcome.drug.quantity)
    }

    @Test
    fun `приём, исчерпавший препарат, уносит все планы`() {
        val drug = drug(10.0).createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice, qty(10.0))

        assertTrue(outcome.drugExhausted)
        assertNull(outcome.plan)
        assertTrue(outcome.drug.plans.isEmpty())
    }

    // ── Пересчёт планов ──────────────────────────────────────────────────────────

    @Test
    fun `списание сжимает планы пропорционально`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(60.0))
            .createPlan(bob, qty(40.0))

        val outcome = drug.consume(qty(50.0))

        assertFalse(outcome.exhausted)
        assertQty(30.0, outcome.drug.planOf(alice)!!.plannedAmount)
        assertQty(20.0, outcome.drug.planOf(bob)!!.plannedAmount)
        assertQty(50.0, outcome.drug.plannedTotal)
    }

    @Test
    fun `планы, укладывающиеся в остаток, не трогаются`() {
        val drug = drug(100.0)
            .createPlan(alice, qty(20.0))
            .createPlan(bob, qty(20.0))

        val left = drug.consume(qty(50.0)).drug

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

        val left = drug.consume(qty(30.0)).drug

        assertQty(20.0, left.planOf(alice)!!.plannedAmount)
        assertQty(40.0, left.planOf(bob)!!.plannedAmount)
        assertTrue(left.plannedTotal <= left.quantity, "сумма планов не превышает остаток")
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
