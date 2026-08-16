package org.kert0n.medappserver.db.model

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty

/**
 * Инварианты агрегата без Spring и без базы.
 *
 * Правила проверяются там, где они записаны, поэтому проверке не нужны ни контекст, ни
 * контейнер: агрегат — обычный объект, и всё, что он решает, он решает по своим полям.
 * Тем же самым правилам, но уже вместе с записью в базу, посвящены `DrugServiceTest` и
 * `PlanReconciliationTest`.
 */
class DrugAggregateTest {

    private val kit = MedKit()
    private val alice = User(UUID.randomUUID(), "alice")
    private val bob = User(UUID.randomUUID(), "bob")

    private fun drug(quantity: Double): Drug =
        Drug.create(medKit = kit, name = "Aspirin", quantity = qty(quantity), quantityUnit = "pills")

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
        val drug = drug(10.0)

        drug.increaseQuantityTo(qty(25.0))
        assertQty(25.0, drug.quantity)

        assertFailsWith<QuantityNotIncreased> { drug.increaseQuantityTo(qty(24.0)) }
        assertFailsWith<QuantityNotIncreased> { drug.increaseQuantityTo(qty(25.0)) }
        assertQty(25.0, drug.quantity)
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
        val drug = drug(10.0)
        drug.createPlan(alice, qty(4.0))

        assertTrue(drug.consume(qty(10.0)), "препарат кончился")
        assertQty(0.0, drug.quantity)
        assertTrue(drug.treatmentPlans.isEmpty(), "планы не переживают препарат")
    }

    // ── Планы ────────────────────────────────────────────────────────────────────

    @Test
    fun `второй план того же пользователя отвергается`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(10.0))

        assertFailsWith<TreatmentPlanAlreadyExists> { drug.createPlan(alice, qty(5.0)) }
        assertEquals(1, drug.treatmentPlans.size)
    }

    @Test
    fun `сумма планов не может превысить остаток`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(70.0))

        assertFailsWith<PlannedAmountExceedsStock> { drug.createPlan(bob, qty(31.0)) }
        // Ровно остаток — можно: запрет на «больше», а не на «всё».
        drug.createPlan(bob, qty(30.0))
        assertQty(100.0, drug.plannedTotal)

        // Свободного не осталось: даже наименьшее возможное количество не резервируется.
        val carol = User(UUID.randomUUID(), "carol")
        assertFailsWith<PlannedAmountExceedsStock> { drug.createPlan(carol, qty("0.000001")) }
    }

    @Test
    fun `изменение плана не считает его прежний размер занятым`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(50.0))
        drug.createPlan(bob, qty(30.0))

        drug.changePlan(bob.id, qty(50.0))
        assertQty(50.0, drug.planOf(bob.id)!!.plannedAmount)

        assertFailsWith<PlannedAmountExceedsStock> { drug.changePlan(bob.id, qty(51.0)) }
    }

    @Test
    fun `отмена несуществующего плана — ошибка, отзыв — нет`() {
        val drug = drug(100.0)

        assertFailsWith<NoSuchTreatmentPlan> { drug.cancelPlan(alice.id) }
        drug.revokePlanOf(alice.id)
    }

    // ── Приём ────────────────────────────────────────────────────────────────────

    @Test
    fun `приём уменьшает и план, и остаток`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(30.0))

        val outcome = drug.applyIntake(alice.id, qty(10.0))

        assertFalse(outcome.drugExhausted)
        assertQty(20.0, outcome.plan!!.plannedAmount)
        assertQty(90.0, drug.quantity)
    }

    @Test
    fun `приём больше собственного плана отвергается`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(10.0))

        assertFailsWith<IntakeExceedsPlan> { drug.applyIntake(alice.id, qty(11.0)) }
        assertQty(100.0, drug.quantity)
    }

    @Test
    fun `приём без плана отвергается`() {
        assertFailsWith<NoSuchTreatmentPlan> { drug(100.0).applyIntake(alice.id, qty(1.0)) }
    }

    @Test
    fun `приём, исчерпавший план, удаляет его и оставляет препарат`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice.id, qty(10.0))

        assertFalse(outcome.drugExhausted)
        assertNull(outcome.plan, "исчерпанный план не возвращается")
        assertNull(drug.planOf(alice.id))
        assertQty(90.0, drug.quantity)
    }

    @Test
    fun `приём, исчерпавший препарат, уносит все планы`() {
        val drug = drug(10.0)
        drug.createPlan(alice, qty(10.0))

        val outcome = drug.applyIntake(alice.id, qty(10.0))

        assertTrue(outcome.drugExhausted)
        assertNull(outcome.plan)
        assertTrue(drug.treatmentPlans.isEmpty())
    }

    // ── Пересчёт планов ──────────────────────────────────────────────────────────

    @Test
    fun `списание сжимает планы пропорционально`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(60.0))
        drug.createPlan(bob, qty(40.0))

        assertFalse(drug.consume(qty(50.0)))

        assertQty(30.0, drug.planOf(alice.id)!!.plannedAmount)
        assertQty(20.0, drug.planOf(bob.id)!!.plannedAmount)
        assertQty(50.0, drug.plannedTotal)
    }

    @Test
    fun `планы, укладывающиеся в остаток, не трогаются`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(20.0))
        drug.createPlan(bob, qty(20.0))

        drug.consume(qty(50.0))

        assertQty(20.0, drug.planOf(alice.id)!!.plannedAmount)
        assertQty(20.0, drug.planOf(bob.id)!!.plannedAmount)
    }

    /**
     * Коэффициент 60/90 — бесконечная дробь, и отдельным числом его не выразить. Умножение
     * до деления оставляет 30 → 20 точным; сумма сжатых планов не должна превысить остаток.
     */
    @Test
    fun `бесконечный коэффициент не создаёт количества из воздуха`() {
        val drug = drug(90.0)
        drug.createPlan(alice, qty(30.0))
        drug.createPlan(bob, qty(60.0))

        drug.consume(qty(30.0))

        assertQty(20.0, drug.planOf(alice.id)!!.plannedAmount)
        assertQty(40.0, drug.planOf(bob.id)!!.plannedAmount)
        assertTrue(drug.plannedTotal <= drug.quantity, "сумма планов не превышает остаток")
    }

    // ── Переезд ──────────────────────────────────────────────────────────────────

    @Test
    fun `переезд уносит планы тех, кто целевую аптечку не видит`() {
        val drug = drug(100.0)
        drug.createPlan(alice, qty(30.0))
        drug.createPlan(bob, qty(30.0))
        val target = MedKit()

        drug.moveTo(target, setOf(alice.id))

        assertEquals(target.id, drug.medKit.id)
        assertQty(30.0, drug.planOf(alice.id)!!.plannedAmount)
        assertNull(drug.planOf(bob.id), "план без доступа не переезжает")
    }
}
