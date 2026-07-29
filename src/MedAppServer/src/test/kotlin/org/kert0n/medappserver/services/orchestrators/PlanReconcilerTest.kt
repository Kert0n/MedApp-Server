package org.kert0n.medappserver.services.orchestrators

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanReconcilerTest {

    @Test
    fun `keeps plans unchanged when they fit stock`() {
        val source = listOf(qty(20.0), qty(30.0))
        assertEquals(source, PlanReconciler.reconcile(qty(60.0), source))
    }

    @Test
    fun `shrinks plans proportionally and never exceeds stock`() {
        val result = PlanReconciler.reconcile(
            stock = qty(50.0),
            plannedAmounts = listOf(qty(60.0), qty(40.0))
        )

        assertQty(30.0, result[0])
        assertQty(20.0, result[1])
        assertTrue(result.reduce { left, right -> left + right } <= qty(50.0))
    }

    @Test
    fun `rounding down preserves stock invariant for repeating fractions`() {
        val result = PlanReconciler.reconcile(
            stock = qty(20.0),
            plannedAmounts = List(30) { qty(1.0) }
        )

        assertTrue(result.fold(qty(0.0)) { sum, amount -> sum + amount } <= qty(20.0))
        result.forEach { assertQty(0.666666, it) }
    }
}
