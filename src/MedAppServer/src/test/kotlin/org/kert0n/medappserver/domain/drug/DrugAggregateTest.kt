package org.kert0n.medappserver.domain.drug

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrugAggregateTest {
    private val medKitId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()
    private val firstUser = UUID.randomUUID()
    private val secondUser = UUID.randomUUID()

    @Test
    fun `create rejects zero and negative stock`() {
        assertFailsWith<InvalidQuantity> { drug("0") }
        assertFailsWith<InvalidQuantity> { drug("-1") }
    }

    @Test
    fun `patch only increases quantity and null means unchanged`() {
        val drug = drug("10")

        drug.patch(DrugPatch(name = null, quantity = BigDecimal("12")))

        assertEquals("Drug", drug.name)
        assertEquals(BigDecimal("12.000000"), drug.quantity)
        assertFailsWith<InvalidQuantity> { drug.patch(DrugPatch(quantity = BigDecimal("12"))) }
        assertFailsWith<InvalidQuantity> { drug.patch(DrugPatch(quantity = BigDecimal("11"))) }
    }

    @Test
    fun `plans cannot reserve more than stock`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        drug.createPlan(firstUser, BigDecimal("7"), plans)

        assertFailsWith<PlannedAmountExceedsStock> {
            drug.createPlan(secondUser, BigDecimal("4"), plans)
        }
        assertFailsWith<PlannedAmountExceedsStock> {
            drug.changePlan(firstUser, BigDecimal("11"), plans)
        }
        assertEquals(BigDecimal("7.000000"), plans.totalPlannedAmount)
    }

    @Test
    fun `consumption reconciles every plan proportionally and rounds down`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        val first = drug.createPlan(firstUser, BigDecimal("6"), plans)
        val second = drug.createPlan(secondUser, BigDecimal("4"), plans)

        val decision = drug.consume(BigDecimal("3"), plans)

        assertFalse(decision.exhausted)
        assertEquals(2, decision.changedPlans.size)
        assertEquals(BigDecimal("4.200000"), first.plannedAmount)
        assertEquals(BigDecimal("2.800000"), second.plannedAmount)
        assertTrue(plans.totalPlannedAmount <= drug.quantity)
    }

    @Test
    fun `fractional reconciliation never exceeds stock`() {
        val drug = drug("1")
        val plans = TreatmentPlanBook(drug.id)
        repeat(3) { index ->
            drug.createPlan(UUID(index.toLong(), index.toLong()), BigDecimal("0.333333"), plans)
        }

        drug.consume(BigDecimal("0.000002"), plans)

        assertEquals(BigDecimal("0.999998"), drug.quantity)
        assertTrue(plans.totalPlannedAmount <= drug.quantity)
        assertTrue(plans.all().all { it.plannedAmount.scale() == 6 })
    }

    @Test
    fun `exhausted drug clears plans as one domain decision`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        drug.createPlan(firstUser, BigDecimal("5"), plans)

        val decision = drug.consume(BigDecimal("10"), plans)

        assertTrue(decision.exhausted)
        assertEquals(1, decision.changedPlans.size)
        assertEquals(0, plans.size)
    }

    @Test
    fun `intake changes stock and own plan together`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        drug.createPlan(firstUser, BigDecimal("3"), plans)

        val first = drug.applyIntake(firstUser, BigDecimal("1.25"), plans)
        val second = drug.applyIntake(firstUser, BigDecimal("1.75"), plans)

        assertEquals(BigDecimal("7.000000"), drug.quantity)
        assertEquals(BigDecimal("1.750000"), first.plan?.plannedAmount)
        assertNull(second.plan)
        assertEquals(0, plans.size)
    }

    @Test
    fun `intake rejects amount above plan or stock without mutation`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        drug.createPlan(firstUser, BigDecimal("2"), plans)

        assertFailsWith<PlannedAmountExceedsStock> {
            drug.applyIntake(firstUser, BigDecimal("3"), plans)
        }
        assertEquals(BigDecimal("10.000000"), drug.quantity)
        assertEquals(BigDecimal("2.000000"), plans.require(firstUser).plannedAmount)

        val inconsistent = drug("1")
        val inconsistentPlans = TreatmentPlanBook(
            inconsistent.id,
            listOf(TreatmentPlan.create(firstUser, inconsistent.id, BigDecimal("2")))
        )
        assertFailsWith<InsufficientStock> {
            inconsistent.applyIntake(firstUser, BigDecimal("1.5"), inconsistentPlans)
    }
}
    @Test
    fun `move keeps only plans whose users can access target`() {
        val drug = drug("10")
        val plans = TreatmentPlanBook(drug.id)
        drug.createPlan(firstUser, BigDecimal("3"), plans)
        drug.createPlan(secondUser, BigDecimal("2"), plans)
        val target = UUID.randomUUID()

        val removed = drug.moveTo(target, setOf(secondUser), plans)

        assertEquals(target, drug.medKitId)
        assertEquals(listOf(firstUser), removed.map(TreatmentPlan::userId))
        assertEquals(listOf(secondUser), plans.all().map(TreatmentPlan::userId))
    }

    private fun drug(quantity: String): Drug = Drug.create(
        CreateDrug(
            id = drugId,
            medKitId = medKitId,
            name = "Drug",
            quantity = BigDecimal(quantity),
            quantityUnit = "mg"
        )
    )
}
