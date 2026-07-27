package org.kert0n.medappserver.integration

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.kert0n.medappserver.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for handleQuantityReduction — the logic that fires when unplanned
 * consumption causes drug.quantity to fall below dbHelper.totalPlannedAmount.
 *
 * Invariant under test:
 *   After any unplanned consumption, dbHelper.totalPlannedAmount == drug.quantity
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
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var drugRepository: DrugRepository

    @Autowired
    private lateinit var usingRepository: UsingRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var usingService: UsingService

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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(20.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(20.0)))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(50.0), alice.id)
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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), charlie.id)
        val drug = dbHelper.freshDrug(kit, 90.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(30.0)))
        usingService.createTreatmentPlan(charlie.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(30.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id), "alice: 30*2/3=20")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id), "bob: 30*2/3=20")
        assertQty(20.0, dbHelper.userPlan(charlie.id, drug.id), "charlie: 30*2/3=20")
        assertQty(60.0, dbHelper.totalPlanned(drug.id), "total==quantity after scaling")

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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(60.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(30.0, alicePlan, "alice: 60*0.5=30")
        assertQty(20.0, bobPlan, "bob: 40*0.5=20")
        assertQty(50.0, dbHelper.totalPlanned(drug.id), "total==quantity")

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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 80.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(40.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        // First reduction
        drugService.consume(drug.id, qty(40.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(40.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id), "after 1st: alice=20")
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id), "after 1st: bob=20")
        assertQty(40.0, dbHelper.totalPlanned(drug.id))

        // Second reduction
        drugService.consume(drug.id, qty(20.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(20.0, dbHelper.drugQuantity(drug.id))
        assertQty(10.0, dbHelper.userPlan(alice.id, drug.id), "after 2nd: alice=10")
        assertQty(10.0, dbHelper.userPlan(bob.id, drug.id), "after 2nd: bob=10")
        assertQty(20.0, dbHelper.totalPlanned(drug.id))

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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(50.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(50.0)))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(75.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(25.0, dbHelper.drugQuantity(drug.id))
        assertQty(12.5, dbHelper.userPlan(alice.id, drug.id), "alice: 50*0.25=12.5")
        assertQty(12.5, dbHelper.userPlan(bob.id, drug.id), "bob: 50*0.25=12.5")
        assertQty(25.0, dbHelper.totalPlanned(drug.id))

        println("✅ Partial deep consumption scales correctly")
    }

    // ── Test 6: THE EDGE CASE — consuming everything disintegrates all plans ─

    /**
     * When drug.quantity reaches 0, factor = 0/dbHelper.totalPlanned = 0.
     * Every plannedAmount becomes 0.0.
     * Plans with plannedAmount == 0 MUST be deleted — ghost rows with 0
     * would corrupt every availability check and dbHelper.totalPlannedAmount formula.
     *
     * Setup: drug=60, alice=20, bob=20, charlie=20 → total=60
     * Consume ALL 60 unplanned:
     *   drug=0, factor=0
     *   all plans → 0.0 → must be DELETED
     *   dbHelper.totalPlanned=0, usingRepository has 0 rows for this drug
     */
    @Test
    fun `Consuming entire stock deletes all treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val charlie = dbHelper.freshUser("charlie")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), charlie.id)
        val drug = dbHelper.freshDrug(kit, 60.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(20.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(20.0)))
        usingService.createTreatmentPlan(charlie.id, UsingCreateDTO(drug.id, qty(20.0)))
        dbHelper.flushAndClear()

        // Sanity: Verify initial state
        assertEquals(3, usingService.findAllByDrug(drug.id).size)

        // Action: Consume ALL 60 unplanned
        drugService.consume(drug.id, qty(60.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify the Drug record is GONE (Privacy-by-Default)
        val deletedDrug = drugRepository.findByIdOrNull(drug.id)
        assertNull(deletedDrug, "Drug record must be deleted from the database when quantity reaches zero")

        // 2. Verify all bridge records (Usings) are GONE
        val remainingUsings = usingRepository.findAllByUsingKeyDrugId(drug.id)
        assertEquals(0, remainingUsings.size, "All treatment plans must be deleted to prevent ghost records")

        // 3. Individual lookups must return null
        assertNull(dbHelper.userPlan(alice.id, drug.id), "Alice's plan must be purged")
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan must be purged")
        assertNull(dbHelper.userPlan(charlie.id, drug.id), "Charlie's plan must be purged")

        println("✅ Privacy-by-default verified: Drug and associated plans completely purged.")
    }

    // ── Test 7: single user plan disintegrates cleanly ───────────────────────

    /**
     * Same as Test 6 but with a single user — ensures the deletion path
     * works when usings.size == 1 (no iteration edge case).
     */
    @Test
    fun `Consuming entire stock with single plan deletes drug and plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(50.0)))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByDrug(drug.id).size)

        // Action: Consume everything in one go
        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify Drug is PURGED
        val remainingDrug = drugRepository.findByIdOrNull(drug.id)
        assertNull(remainingDrug, "Drug record must be deleted to maintain privacy-by-default")

        // 2. Verify Plan is PURGED
        val remainingPlans = usingService.findAllByDrug(drug.id)
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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(6.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(4.0)))
        dbHelper.flushAndClear()

        // --- Step 1: Halve everything ---
        drugService.consume(drug.id, qty(5.0), alice.id)
        dbHelper.flushAndClear()

        // Drug still exists here
        assertQty(5.0, dbHelper.drugQuantity(drug.id))
        assertQty(3.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(2.0, dbHelper.userPlan(bob.id, drug.id))
        assertEquals(2, usingService.findAllByDrug(drug.id).size, "Still 2 plans after partial reduction")

        // --- Step 2: Wipe everything ---
        drugService.consume(drug.id, qty(5.0), alice.id)
        dbHelper.flushAndClear()

        // 1. Verify Drug is PURGED (Privacy-by-Default)
        val remainingDrug = drugRepository.findByIdOrNull(drug.id)
        assertNull(remainingDrug, "Drug record must be deleted when quantity reaches zero")

        // 2. Verify Plans are PURGED
        val remainingPlans = usingService.findAllByDrug(drug.id)
        assertEquals(0, remainingPlans.size, "All plans must be deleted on final step")

        assertNull(dbHelper.userPlan(alice.id, drug.id), "Alice's plan must be null")
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan must be null")

        println("✅ Two-step reduction to zero: Intermediate scaling worked, and final purge was successful")
    }

    // ── Test 9: приём, обнуливший остаток при ненулевом плане ────────────────────────

    /**
     * Приём не должен пытаться сохранить план, чей препарат только что удалён.
     *
     * `handleQuantityReduction` возвращает `Drug?`, где `null` значит «препарат кончился и
     * удалён вместе со всеми планами». Раньше это значение отбрасывалось, и `recordIntake`
     * шёл дальше: план не обнулился, значит `usingRepository.save(using)` — то есть вставка
     * строки с внешним ключом на удалённый препарат.
     *
     * Через сервисы такое состояние не собрать: `createTreatmentPlan` не даёт запланировать
     * больше остатка, а `handleQuantityReduction` держит инвариант «сумма планов не больше
     * остатка». Поэтому план создаётся напрямую репозиторием — тест проверяет само условие в
     * `recordIntake`, а не достижимость состояния. Корректность, которая держится на цепочке
     * проверок в трёх других методах, ломается от первой правки любого из них.
     */
    @Test
    fun `Intake that empties the drug returns null instead of saving an orphan plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 2.0)
        dbHelper.flushAndClear()

        // План больше остатка — состояние, которое сервисы не допускают.
        usingRepository.save(
            Using(
                usingKey = UsingKey(alice.id, drug.id),
                user = userRepository.findByIdOrNull(alice.id)!!,
                drug = drugRepository.findByIdOrNull(drug.id)!!,
                plannedAmount = qty(5.0)
            )
        )
        dbHelper.flushAndClear()

        // Забирает весь остаток; план при этом остаётся ненулевым (5 - 2 = 3).
        val result = drugService.applyIntake(alice.id, drug.id, qty(2.0))

        assertNull(result, "препарат удалён, поэтому возвращать план нечего")
        // Без guard'а здесь падал бы flush: план сохранялся бы со ссылкой на удалённую строку.
        dbHelper.flushAndClear()

        assertNull(drugRepository.findByIdOrNull(drug.id), "препарат с нулевым остатком удаляется")
        assertEquals(0, usingService.findAllByDrug(drug.id).size, "планы уходят каскадом")

        println("✅ Guard на удалённый препарат сработал, сирота не сохранён")
    }
}