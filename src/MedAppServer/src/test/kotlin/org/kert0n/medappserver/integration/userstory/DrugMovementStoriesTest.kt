package org.kert0n.medappserver.integration.userstory

import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.*
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class DrugMovementStoriesTest {

    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper


    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator


    /**
     * Story 11: Moving drugs between medkits preserves treatment plans
     * 
     * Validates: Drug move, treatment plan integrity
     */
    @Test
    fun `Story 11 - Moving drug between medkits`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)

        val homeKit = medKitService.create(userData.id)
        val travelKit = medKitService.create(userData.id)

        val painkiller = Drug(
            id = UUID.randomUUID(), name = "Ibuprofen",
            quantity = Quantity(qty(60.0), dbHelper.unit()),
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKitId = homeKit.id
        )
        dbHelper.insert(painkiller)
        entityManager.flush()

        // Create treatment plan
        drugService.createPlanLatest(userData.id, painkiller.id, qty(20.0))
        entityManager.flush()

        // Move drug to travel kit
        medKitDrugOrchestrator.moveDrugLatest(drugService, painkiller.id, travelKit.id, userData.id)
        entityManager.flush()
        entityManager.clear()

        // Drug is in travel kit
        val movedDrug = dbHelper.drug(painkiller.id)
        assertNotNull(movedDrug)
        assertEquals(travelKit.id, movedDrug.medKitId)

        // Home kit is empty
        val homeKitDrugs = drugService.ofMedKit(homeKit.id)
        assertTrue(homeKitDrugs.isEmpty())

        // Travel kit has the drug
        val travelKitDrugs = drugService.ofMedKit(travelKit.id)
        assertEquals(1, travelKitDrugs.size)

        // Treatment plan still exists
        val plan = dbHelper.userPlan(userData.id, painkiller.id)
        assertNotNull(plan, "Treatment plan should survive drug move")
        assertQty(20.0, plan)

        println("✅ Story 11 passed: Drug moved between medkits with treatment plan intact")
    }

    /**
     * Story 12: Update treatment plan correctly checks available quantity
     * 
     * Validates: changing a plan does not double-count the caller's own plan
     */
    @Test
    fun `Story 12 - Updating treatment plan correctly checks available quantity`() {
        val anna = User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}")
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medkit.id, anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val drugData = Drug(
            id = UUID.randomUUID(), name = "Medicine X",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null, manufacturer = null, country = null,
            description = null, medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Anna plans 40, Bob plans 30 (total 70, available 30)
        drugService.createPlanLatest(anna.id, drugData.id, qty(40.0))
        drugService.createPlanLatest(bob.id, drugData.id, qty(30.0))
        entityManager.flush()

        // Anna should be able to increase her plan to 70 (available for her = 100 - 30 (bob) = 70)
        val updated = drugService.changePlanLatest(anna.id, drugData.id, qty(70.0))
        assertQty(70.0, updated.plannedAmount)
        entityManager.flush()
        entityManager.clear()
        // Total planned should now be 100 (70 + 30)
        assertQty(100.0, dbHelper.totalPlanned(drugData.id))

        // Anna should NOT be able to increase to 71 (exceeds available)
        assertFailsWith<PlannedAmountExceedsStock> {
            drugService.changePlanLatest(anna.id, drugData.id, qty(71.0))
        }

        println("✅ Story 12 passed: Treatment plan update correctly checks available quantity")
    }

    /**
     * Story 13: Deleting a drug cascades to remove associated treatment plans
     * 
     * Validates: Cascade delete behavior, orphan removal
     */
    @Test
    fun `Story 13 - Deleting drug removes its treatment plans`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
        val drugData = Drug(
            id = UUID.randomUUID(), name = "Expired Drug",
            quantity = Quantity(qty(50.0), dbHelper.unit()), formType = null,
            category = null, manufacturer = null, country = null,
            description = null, medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Create treatment plan
        drugService.createPlanLatest(userData.id, drugData.id, qty(25.0))
        entityManager.flush()
        entityManager.clear()

        // Verify plan exists
        val plan = dbHelper.userPlan(userData.id, drugData.id)
        assertNotNull(plan)

        // Delete the drug
        drugService.deleteLatest(drugData.id, userData.id)
        entityManager.flush()
        entityManager.clear()

        // Drug should be gone
        val deletedDrug = dbHelper.drug(drugData.id)
        assertNull(deletedDrug)

        // Treatment plan should also be gone (cascade)
        val deletedPlan = dbHelper.userPlan(userData.id, drugData.id)
        assertNull(deletedPlan)

        println("✅ Story 13 passed: Deleting drug removed its treatment plans")
    }

    /**
     * Story 14: Migrating a drug to a private medkit strips access from former shared users
     * * Validates: Migration Security Audit (The "Void Pointer" fix)
     */
    @Test
    fun `Story 14 - Moving shared drug to private medkit removes other users treatment plans`() {
        // Setup: Anna, Bob, and Charlie share an Old MedKit
        val anna = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}"))
        val bob = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))
        val charlie = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "charlie_${UUID.randomUUID()}"))

        val oldKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(oldKit.id, anna.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(oldKit.id, anna.id), charlie.id)

        // Setup: Anna and Bob share a New MedKit (Charlie is excluded)
        val newKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(newKit.id, anna.id), bob.id)

        // Add drug to old kit
        val drugData = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Special Meds", quantity = Quantity(qty(90.0), dbHelper.unit()), medKitId = oldKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Everyone creates a plan for 30 pills
        drugService.createPlanLatest(anna.id, drugData.id, qty(30.0))
        drugService.createPlanLatest(bob.id, drugData.id, qty(30.0))
        drugService.createPlanLatest(charlie.id, drugData.id, qty(30.0))

        entityManager.flush()
        entityManager.clear()

        // Anna deletes the old kit and migrates to the new kit
        medKitDrugOrchestrator.deleteLatest(medKitService, oldKit.id, anna.id, newKit.id)

        entityManager.flush()
        entityManager.clear()

        // Verify: Anna and Bob still have their plans. Charlie's plan was deleted.
        assertNotNull(dbHelper.userPlan(anna.id, drugData.id), "Anna should keep her plan")
        assertNotNull(dbHelper.userPlan(bob.id, drugData.id), "Bob should keep his plan")
        assertNull(
            dbHelper.userPlan(charlie.id, drugData.id),
            "Charlie's plan MUST be deleted for security"
        )

        println("✅ Story 14 passed: Migration security successfully audited treatment plans")
    }

    /**
     * Story 15: Heavy consumption scales down shared treatment plans proportionally
     * * Validates: plan reconciliation precision
     */
    @Test
    fun `Story 15 - Consuming below reserved threshold scales plans proportionally`() {
        val anna = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}"))
        val bob = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))

        val kit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, anna.id), bob.id)

        // Drug has 100 total
        val drugData = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Shared Vitamins", quantity = Quantity(qty(100.0), dbHelper.unit()), medKitId = kit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Anna plans 60, Bob plans 40. Total planned = 100.
        drugService.createPlanLatest(anna.id, drugData.id, qty(60.0))
        drugService.createPlanLatest(bob.id, drugData.id, qty(40.0))

        entityManager.flush()
        entityManager.clear()

        // Bob consumes 50 pills (ignoring his plan limit for emergency)
        // Drug quantity drops to 50.
        // Factor should be: 50 / 100 = 0.5
        drugService.consumeLatest(drugData.id, qty(50.0), bob.id)

        entityManager.flush()
        entityManager.clear()

        val annaPlan = dbHelper.userPlan(anna.id, drugData.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drugData.id)!!

        // Plans should be exactly halved
        assertQty(30.0, annaPlan, "Anna's plan should scale from 60 to 30")
        assertQty(20.0, bobPlan, "Bob's plan should scale from 40 to 20")

        println("✅ Story 15 passed: Treatment plans scaled proportionally after heavy consumption")
    }

    /**
     * Story 16: Partial migration prevents orphan removal
     * * Validates: Explicit `targetMedKit.drugs.add(drug)` fix
     */
    @Test
    fun `Story 16 - Moving single drug preserves it from orphan removal`() {
        val userData = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}"))

        val sourceKit = medKitService.create(userData.id)
        val targetKit = medKitService.create(userData.id)

        val drugDataToMove = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Moving Pill", quantity = Quantity(qty(10.0), dbHelper.unit()), medKitId = sourceKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        val drugDataToStay = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Staying Pill", quantity = Quantity(qty(10.0), dbHelper.unit()), medKitId = sourceKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        entityManager.flush()
        entityManager.clear()

        // Move ONLY one drug
        medKitDrugOrchestrator.moveDrugLatest(drugService, drugDataToMove.id, targetKit.id, userData.id)

        entityManager.flush()
        entityManager.clear()

        // Verify it wasn't deleted by orphan removal during the move
        val movedDrug = dbHelper.drug(drugDataToMove.id)
        assertNotNull(movedDrug, "Moved drug must not be deleted")
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug should point to new kit")

        val stayingDrug = dbHelper.drug(drugDataToStay.id)
        assertNotNull(stayingDrug, "Staying drug must not be affected")
        assertEquals(sourceKit.id, stayingDrug.medKitId)

        println("✅ Story 16 passed: Moving a single drug prevented orphan removal")
    }
}
