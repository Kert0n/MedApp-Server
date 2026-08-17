package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.*
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Tests for plan reconciliation — the rule that fires when unplanned
 * consumption causes drug.quantity to fall below dbHelper.storedPlannedTotal.
 *
 * Invariant under test:
 *   After any unplanned consumption, dbHelper.storedPlannedTotal == drug.quantity
 *   (because plans are scaled by factor = newQuantity / olddbHelper.totalPlanned)
 *
 * Critical edge case:
 *   When drug.quantity reaches 0, every plan's plannedAmount becomes 0.
 *   Plans with plannedAmount == 0 must be DELETED, not left as ghost rows.
 */
@PostgresIntegrationTest
@Transactional
class QuantityReductionTests {

    @Autowired
    private lateinit var treatmentPlanRepository: TreatmentPlanRepository


    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── Test 1: no scaling when quantity stays above dbHelper.totalPlanned ────────────

    /**
     * Consuming stock that is NOT reserved must not touch any plan.
     *
     * Setup: drug=100, alice=20, bob=20 → total=40, slack=60
     * Consume 50 (within slack):
     *   drug=50 > total=40 → no scaling
     *   alice=20, bob=20 unchanged
     */
    @Test
    fun `Consuming within slack leaves all plans untouched`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(20.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(20.0))
        dbHelper.flushAndClear()

        drugService.consumeLatest(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id), "alice: untouched")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id), "bob: untouched")
        assertQty(40.0, dbHelper.totalPlanned(drug.id), "total: untouched")

        println("✅ No scaling within slack")
    }

    // ── Test 2: symmetric plans scale proportionally ─────────────────────────

    /**
     * Setup: drug=90, alice=30, bob=30, charlie=30 → total=90 (zero slack)
     * Consume 30 unplanned:
     *   drug=60, factor=60/90=2/3
     *   alice=20, bob=20, charlie=20, total=60
     */
    @Test
    fun `Symmetric plans scale proportionally on unplanned consumption`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val charlie = dbHelper.freshUser("charlie")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), charlie.id)
        val drug = dbHelper.freshDrug(kit.id, 90.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(30.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(30.0))
        drugService.createPlanLatest(charlie.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        drugService.consumeLatest(drug.id, qty(30.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id)!!, "alice: 30*2/3=20")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!, "bob: 30*2/3=20")
        assertQty(20.0, dbHelper.userPlan(charlie.id, drug.id)!!, "charlie: 30*2/3=20")
        assertQty(60.0, dbHelper.totalPlanned(drug.id)!!, "total==quantity after scaling")

        println("✅ Symmetric proportional scaling")
    }

    // ── Test 3: asymmetric plans scale proportionally ────────────────────────

    /**
     * Plans are NOT equal — the ratio between them must be preserved.
     *
     * Setup: drug=100, alice=60, bob=40 → total=100 (zero slack)
     * Consume 50 unplanned:
     *   drug=50, factor=50/100=0.5
     *   alice=30, bob=20, total=50
     * Ratio alice:bob was 3:2, must remain 3:2 after scaling.
     */
    @Test
    fun `Asymmetric plans preserve their ratio after scaling`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(60.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        drugService.consumeLatest(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(30.0, alicePlan, "alice: 60*0.5=30")
        assertQty(20.0, bobPlan, "bob: 40*0.5=20")
        assertQty(50.0, dbHelper.totalPlanned(drug.id)!!, "total==quantity")

        // ratio preserved
        assertQty(3.0 / 2.0, alicePlan / bobPlan, "ratio 3:2 preserved")

        println("✅ Asymmetric ratio preserved")
    }

    // ── Test 4: sequential reductions compound correctly ────────────────────

    /**
     * Two successive unplanned consumptions both trigger scaling.
     *
     * Setup: drug=80, alice=40, bob=40 → total=80
     *
     * Consume 40 unplanned → drug=40, factor=0.5
     *   alice=20, bob=20, total=40
     *
     * Consume 20 more unplanned → drug=20, factor=20/40=0.5
     *   alice=10, bob=10, total=20
     */
    @Test
    fun `Sequential unplanned consumptions compound scaling correctly`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 80.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(40.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // First reduction
        drugService.consumeLatest(drug.id, qty(40.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(40.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id)!!, "after 1st: alice=20")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!, "after 1st: bob=20")
        assertQty(40.0, dbHelper.totalPlanned(drug.id)!!)

        // Second reduction
        drugService.consumeLatest(drug.id, qty(20.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(20.0, dbHelper.drugQuantity(drug.id))
        assertQty(10.0, dbHelper.userPlan(alice.id, drug.id)!!, "after 2nd: alice=10")
        assertQty(10.0, dbHelper.userPlan(bob.id, drug.id)!!, "after 2nd: bob=10")
        assertQty(20.0, dbHelper.totalPlanned(drug.id)!!)

        println("✅ Sequential scaling compounds correctly")
    }

    // ── Test 5: partial consumption into the reserved zone ──────────────────

    /**
     * Consuming into the reserved zone but not to zero.
     * Only the portion exceeding the slack triggers scaling.
     *
     * Setup: drug=100, alice=50, bob=50 → total=100
     * Consume 75 unplanned:
     *   drug=25, factor=25/100=0.25
     *   alice=12.5, bob=12.5, total=25
     */
    @Test
    fun `Partial consumption deep into reserved zone scales correctly`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(50.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        drugService.consumeLatest(drug.id, qty(75.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(25.0, dbHelper.drugQuantity(drug.id))
        assertQty(12.5, dbHelper.userPlan(alice.id, drug.id)!!, "alice: 50*0.25=12.5")
        assertQty(12.5, dbHelper.userPlan(bob.id, drug.id)!!, "bob: 50*0.25=12.5")
        assertQty(25.0, dbHelper.totalPlanned(drug.id)!!)

        println("✅ Partial deep consumption scales correctly")
    }

    // ── Test 6: THE EDGE CASE — consuming everything disintegrates all plans ─

    /**
     * When drug.quantity reaches 0, factor = 0/dbHelper.totalPlanned = 0.
     * Every plannedAmount becomes 0.0.
     * Plans with plannedAmount == 0 MUST be deleted — ghost rows with 0
     * would corrupt every availability check and dbHelper.storedPlannedTotal formula.
     *
     * Setup: drug=60, alice=20, bob=20, charlie=20 → total=60
     * Consume ALL 60 unplanned:
     *   drug=0, factor=0
     *   all plans → 0.0 → must be DELETED
     *   dbHelper.totalPlanned=0, treatmentPlanRepository has 0 rows for this drug
     */
    @Test
    fun `Consuming entire stock deletes all treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val charlie = dbHelper.freshUser("charlie")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), charlie.id)
        val drug = dbHelper.freshDrug(kit.id, 60.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(20.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(20.0))
        drugService.createPlanLatest(charlie.id, drug.id, qty(20.0))
        dbHelper.flushAndClear()

        // Sanity: Verify initial state
        assertEquals(3, (dbHelper.drug(drug.id)?.plans ?: emptyList()).size)

        // Action: Consume ALL 60 unplanned
        drugService.consumeLatest(drug.id, qty(60.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify the Drug record is GONE (Privacy-by-Default)
        val deletedDrug = dbHelper.drug(drug.id)
        assertNull(deletedDrug, "Drug record must be deleted from the database when quantity reaches zero")

        // 2. Verify all bridge records (treatment plans) are GONE
        val remainingPlans = (dbHelper.drug(drug.id)?.plans ?: emptyList())
        assertEquals(0, remainingPlans.size, "All treatment plans must be deleted to prevent ghost records")

        // 3. Individual lookups must return null
        assertNull(dbHelper.userPlan(alice.id, drug.id), "Alice's plan must be purged")
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan must be purged")
        assertNull(dbHelper.userPlan(charlie.id, drug.id), "Charlie's plan must be purged")

        println("✅ Privacy-by-default verified: Drug and associated plans completely purged.")
    }

    // ── Test 7: single user plan disintegrates cleanly ───────────────────────

    /**
     * Same as Test 6 but with a single user — ensures the deletion path
     * works when plans.size == 1 (no iteration edge case).
     */
    @Test
    fun `Consuming entire stock with single plan deletes drug and plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        assertEquals(1, (dbHelper.drug(drug.id)?.plans ?: emptyList()).size)

        // Action: Consume everything in one go
        drugService.consumeLatest(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify Drug is PURGED
        val remainingDrug = dbHelper.drug(drug.id)
        assertNull(remainingDrug, "Drug record must be deleted to maintain privacy-by-default")

        // 2. Verify Plan is PURGED
        val remainingPlans = (dbHelper.drug(drug.id)?.plans ?: emptyList())
        assertEquals(0, remainingPlans.size, "Associated treatment plan must be purged")

        // 3. Direct lookup should fail
        assertNull(dbHelper.userPlan(alice.id, drug.id), "Individual plan lookup must return null")

        println("✅ Single plan and drug disintegrated correctly")
    }

    // ── Test 8: near-zero consumption — epsilon plans must also be deleted ───

    /**
     * After many small reductions the plans approach 0 without reaching it
     * in a single step. Verifies that a consumption that brings quantity
     * to exactly 0 still cleans up regardless of the accumulated fractional value.
     *
     * Setup: drug=10, alice=6, bob=4 → total=10
     * Consume 5 → drug=5, factor=0.5 → alice=3, bob=2
     * Consume 5 → drug=0, factor=0   → alice=0, bob=0 → DELETED
     */
    @Test
    fun `Two-step reduction to zero cleans up all plans on final step`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        drugService.createPlanLatest(alice.id, drug.id, qty(6.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(4.0))
        dbHelper.flushAndClear()

        // --- Step 1: Halve everything ---
        drugService.consumeLatest(drug.id, qty(5.0), alice.id)
        dbHelper.flushAndClear()

        // Drug still exists here
        assertQty(5.0, dbHelper.drugQuantity(drug.id))
        assertQty(3.0, dbHelper.userPlan(alice.id, drug.id)!!)
        assertQty(2.0, dbHelper.userPlan(bob.id, drug.id)!!)
        assertEquals(2, (dbHelper.drug(drug.id)?.plans ?: emptyList()).size, "Still 2 plans after partial reduction")

        // --- Step 2: Wipe everything ---
        drugService.consumeLatest(drug.id, qty(5.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify Drug is PURGED (Privacy-by-Default)
        val remainingDrug = dbHelper.drug(drug.id)
        assertNull(remainingDrug, "Drug record must be deleted when quantity reaches zero")

        // 2. Verify Plans are PURGED
        val remainingPlans = (dbHelper.drug(drug.id)?.plans ?: emptyList())
        assertEquals(0, remainingPlans.size, "All plans must be deleted on final step")

        assertNull(dbHelper.userPlan(alice.id, drug.id), "Alice's plan must be null")
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan must be null")

        println("✅ Two-step reduction to zero: Intermediate scaling worked, and final purge was successful")
    }
}