package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Tracks planned quantity precisely through every operation.
 *
 * Core invariant that must always hold:
 *   drug.dbHelper.storedPlannedTotal <= drug.quantity
 *
 * After a PLANNED intake (recordIntake):
 *   - The consuming user's plan shrinks by exactly quantityConsumed
 *   - Every other user's plan is UNCHANGED
 *   - drug.quantity shrinks by exactly quantityConsumed
 *   - dbHelper.storedPlannedTotal shrinks by exactly quantityConsumed
 *   - The invariant is preserved trivially (no emergency scaling needed)
 *
 * After an EMERGENCY consumption (consumeDrug directly, bypassing a plan):
 *   - drug.quantity shrinks by quantityConsumed
 *   - If dbHelper.storedPlannedTotal now exceeds quantity, ALL plans scale down proportionally
 *   - dbHelper.storedPlannedTotal == drug.quantity after scaling
 */
@PostgresIntegrationTest
@Transactional
class PlannedQuantityTrackingTests {

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ──────────────────────────────────────────────────────────
    // Test 1 — Planned intake with slack: no scaling should ever occur
    // ──────────────────────────────────────────────────────────

    /**
     * Setup: drug=120, alice=40, bob=30 → total=70, slack=50
     *
     * Because quantity > dbHelper.totalPlanned at every point,
     * plan reconciliation must NEVER fire.
     *
     * After alice takes 10:  alice=30, bob=30, drug=110, total=60
     * After bob  takes  5:  alice=30, bob=25, drug=105, total=55
     */
    @Test
    fun `Planned intakes with slack - other users plans are never touched`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")

        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)

        val drug = dbHelper.freshDrug(kit.id, 120.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(40.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        // Sanity: initial state
        assertQty(120.0, dbHelper.drugQuantity(drug.id))
        assertQty(70.0, dbHelper.totalPlanned(drug.id))
        assertQty(40.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id))

        // ── Alice takes 10 (within her plan of 40) ──
        drugService.recordIntake(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertQty(110.0, dbHelper.drugQuantity(drug.id), "drug: 120-10=110")
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id), "alice: 40-10=30")
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id), "bob: unchanged at 30")
        assertQty(60.0, dbHelper.totalPlanned(drug.id), "total: 30+30=60")

        // ── Bob takes 5 (within his plan of 30) ──
        drugService.recordIntake(bob.id, drug.id, qty(5.0))
        dbHelper.flushAndClear()

        assertQty(105.0, dbHelper.drugQuantity(drug.id), "drug: 110-5=105")
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id), "alice: unchanged at 30")
        assertQty(25.0, dbHelper.userPlan(bob.id, drug.id), "bob: 30-5=25")
        assertQty(55.0, dbHelper.totalPlanned(drug.id), "total: 30+25=55")

        // Invariant: planned never exceeds quantity
        assertTrue(dbHelper.totalPlanned(drug.id)!! <= dbHelper.drugQuantity(drug.id)!!)

        println("✅ Slack test passed")
    }

    // ──────────────────────────────────────────────────────────
    // Test 2 — Planned intakes on a FULLY BOOKED drug
    // ──────────────────────────────────────────────────────────

    /**
     * This is the critical case: quantity == dbHelper.totalPlanned from the start.
     *
     * Setup: drug=90, alice=30, bob=30, charlie=30 → total=90 (zero slack)
     *
     * After alice   takes 1: alice=29, bob=30, charlie=30, drug=89, total=89
     * After bob     takes 1: alice=29, bob=29, charlie=30, drug=88, total=88
     * After charlie takes 1: alice=29, bob=29, charlie=29, drug=87, total=87
     *
     * At no point should any user's plan be modified by a cascade
     * triggered by another user's planned intake.
     */
    @Test
    fun `Planned intakes on fully booked drug - each intake reduces exactly one plan by exactly one dose`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val charlie = dbHelper.freshUser("charlie")

        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), charlie.id)

        val drug = dbHelper.freshDrug(kit.id, 90.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        drugService.createPlan(charlie.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        // Initial state: zero slack
        assertQty(90.0, dbHelper.drugQuantity(drug.id))
        assertQty(90.0, dbHelper.totalPlanned(drug.id))
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id))
        assertQty(30.0, dbHelper.userPlan(charlie.id, drug.id))

        // ── Alice takes 1 ──
        drugService.recordIntake(alice.id, drug.id, qty(1.0))
        dbHelper.flushAndClear()

        assertQty(89.0, dbHelper.drugQuantity(drug.id), "drug: 90-1=89")
        assertQty(29.0, dbHelper.userPlan(alice.id, drug.id), "alice: 30-1=29")
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id), "bob: untouched")
        assertQty(30.0, dbHelper.userPlan(charlie.id, drug.id), "charlie: untouched")
        assertQty(89.0, dbHelper.totalPlanned(drug.id), "total: 29+30+30=89")

        // ── Bob takes 1 ──
        drugService.recordIntake(bob.id, drug.id, qty(1.0))
        dbHelper.flushAndClear()

        assertQty(88.0, dbHelper.drugQuantity(drug.id), "drug: 89-1=88")
        assertQty(29.0, dbHelper.userPlan(alice.id, drug.id), "alice: still 29")
        assertQty(29.0, dbHelper.userPlan(bob.id, drug.id), "bob: 30-1=29")
        assertQty(30.0, dbHelper.userPlan(charlie.id, drug.id), "charlie: untouched")
        assertQty(88.0, dbHelper.totalPlanned(drug.id), "total: 29+29+30=88")

        // ── Charlie takes 1 ──
        drugService.recordIntake(charlie.id, drug.id, qty(1.0))
        dbHelper.flushAndClear()

        assertQty(87.0, dbHelper.drugQuantity(drug.id), "drug: 88-1=87")
        assertQty(29.0, dbHelper.userPlan(alice.id, drug.id), "alice: still 29")
        assertQty(29.0, dbHelper.userPlan(bob.id, drug.id), "bob: still 29")
        assertQty(29.0, dbHelper.userPlan(charlie.id, drug.id), "charlie: 30-1=29")
        assertQty(87.0, dbHelper.totalPlanned(drug.id), "total: 29+29+29=87")

        // Invariant preserved
        assertEquals(
            dbHelper.drugQuantity(drug.id), dbHelper.totalPlanned(drug.id),
            "On a fully-booked drug, quantity and dbHelper.totalPlanned must stay equal after planned intakes"
        )

        println("✅ Fully-booked planned intake test passed")
    }

    // ──────────────────────────────────────────────────────────
    // Test 3 — Emergency consumption triggers proportional scaling
    // ──────────────────────────────────────────────────────────

    /**
     * Emergency = consumeDrug called directly (no treatment plan, or ignoring it).
     *
     * Setup: drug=90, alice=30, bob=30, charlie=30 → total=90
     *
     * Bob emergency-consumes 30 tablets (beyond any plan):
     *   drug: 90→60
     *   dbHelper.totalPlanned 90 > 60 → scale factor = 60/90 = 2/3
     *   alice: 30 * 2/3 = 20
     *   bob:   30 * 2/3 = 20
     *   charlie: 30 * 2/3 = 20
     *   dbHelper.totalPlanned: 60 = drug.quantity ✓
     */
    @Test
    fun `Emergency consumption scales all treatment plans proportionally`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val charlie = dbHelper.freshUser("charlie")

        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), charlie.id)

        val drug = dbHelper.freshDrug(kit.id, 90.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        drugService.createPlan(charlie.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        // Bob emergency-consumes 30 (ignores the plan system)
        drugService.consume(drug.id, qty(30.0), bob.id)
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.drugQuantity(drug.id), "drug: 90-30=60")

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!
        val charliePlan = dbHelper.userPlan(charlie.id, drug.id)!!
        val total = dbHelper.totalPlanned(drug.id)

        // factor = 60/90 = 2/3; each plan was 30 → 20
        assertQty(20.0, alicePlan, "alice: 30 * 2/3 = 20")
        assertQty(20.0, bobPlan, "bob: 30 * 2/3 = 20")
        assertQty(20.0, charliePlan, "charlie: 30 * 2/3 = 20")
        assertQty(60.0, total!!, "dbHelper.totalPlanned must equal drug.quantity after scaling")

        println("✅ Emergency consumption scaling test passed")
    }

    // ──────────────────────────────────────────────────────────
    // Test 4 — Mixed workflow: planned intakes then emergency
    // ──────────────────────────────────────────────────────────

    /**
     * Combines normal intakes with an emergency, checking each step.
     *
     * Setup:  drug=120, alice=40, bob=40 → total=80, slack=40
     *
     * Step 1 – Alice takes 10 (planned):
     *   alice=30, bob=40, drug=110, total=70, slack=40
     *
     * Step 2 – Bob takes 10 (planned):
     *   alice=30, bob=30, drug=100, total=60, slack=40
     *
     * Step 3 – Emergency consume 60 (no plan):
     *   drug: 100→40
     *   total was 60 > 40 → scale = 40/60 = 2/3
     *   alice: 30 * 2/3 = 20
     *   bob:   30 * 2/3 = 20
     *   total = 40 ✓
     *
     * Step 4 – Alice takes 5 (planned intake, within new plan of 20):
     *   alice=15, bob=20, drug=35, total=35
     */
    @Test
    fun `Mixed planned intakes and emergency consumption - full lifecycle`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")

        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)

        val drug = dbHelper.freshDrug(kit.id, 120.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(40.0))
        drugService.createPlan(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        assertQty(120.0, dbHelper.drugQuantity(drug.id))
        assertQty(80.0, dbHelper.totalPlanned(drug.id))
        assertQty(40.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(40.0, dbHelper.userPlan(bob.id, drug.id))

        // ── Step 1: Alice takes 10 ──
        drugService.recordIntake(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertQty(110.0, dbHelper.drugQuantity(drug.id), "step1 drug")
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id), "step1 alice: 40-10=30")
        assertQty(40.0, dbHelper.userPlan(bob.id, drug.id), "step1 bob: unchanged")
        assertQty(70.0, dbHelper.totalPlanned(drug.id), "step1 total: 30+40=70")

        // ── Step 2: Bob takes 10 ──
        drugService.recordIntake(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertQty(100.0, dbHelper.drugQuantity(drug.id), "step2 drug")
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id), "step2 alice: unchanged")
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id), "step2 bob: 40-10=30")
        assertQty(60.0, dbHelper.totalPlanned(drug.id), "step2 total: 30+30=60")

        // ── Step 3: Emergency consume 60 ──
        drugService.consume(drug.id, qty(60.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(40.0, dbHelper.drugQuantity(drug.id), "step3 drug: 100-60=40")
        // scale = 40/60 = 2/3
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id)!!, "step3 alice: 30*2/3=20")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!, "step3 bob: 30*2/3=20")
        assertQty(40.0, dbHelper.totalPlanned(drug.id)!!, "step3 total=40=drug.quantity")

        // ── Step 4: Alice takes 5 of her new plan of 20 ──
        drugService.recordIntake(alice.id, drug.id, qty(5.0))
        dbHelper.flushAndClear()

        assertQty(35.0, dbHelper.drugQuantity(drug.id), "step4 drug: 40-5=35")
        assertQty(15.0, dbHelper.userPlan(alice.id, drug.id)!!, "step4 alice: 20-5=15")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!, "step4 bob: unchanged at 20")
        assertQty(35.0, dbHelper.totalPlanned(drug.id)!!, "step4 total: 15+20=35")

        // Final invariant
        assertQty(dbHelper.drugQuantity(drug.id)!!, dbHelper.totalPlanned(drug.id)!!, "quantity and dbHelper.totalPlanned must be equal: all stock is reserved")

        println("✅ Mixed lifecycle test passed")
    }
}
