package org.kert0n.medappserver.integration.userstory

import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.*
import org.kert0n.medappserver.db.model.PlannedAmountExceedsStock
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class DrugMovementStoriesTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var drugRepository: DrugRepository

    @Autowired
    private lateinit var treatmentPlanRepository: TreatmentPlanRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKitDrugServices: MedKitDrugServices

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService

    /**
     * Story 11: Moving drugs between medkits preserves treatment plans
     * 
     * Validates: Drug move, treatment plan integrity
     */
    @Test
    fun `Story 11 - Moving drug between medkits`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)

        val homeKit = medKitService.createNew(user.id)
        val travelKit = medKitService.createNew(user.id)

        val painkiller = Drug(
            id = UUID.randomUUID(), name = "Ibuprofen",
            quantity = qty(60.0), quantityUnit = "tablets", formType = "tablet",
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKit = homeKit
        )
        drugRepository.save(painkiller)
        entityManager.flush()

        // Create treatment plan
        drugService.createPlan(user.id, painkiller.id, qty(20.0))
        entityManager.flush()

        // Move drug to travel kit
        medKitDrugServices.moveDrug(painkiller.id, travelKit.id, user.id)
        entityManager.flush()
        entityManager.clear()

        // Drug is in travel kit
        val movedDrug = drugRepository.findById(painkiller.id).orElse(null)
        assertNotNull(movedDrug)
        assertEquals(travelKit.id, movedDrug.medKit.id)

        // Home kit is empty
        val homeKitDrugs = drugRepository.findAllByMedKitId(homeKit.id)
        assertTrue(homeKitDrugs.isEmpty())

        // Travel kit has the drug
        val travelKitDrugs = drugRepository.findAllByMedKitId(travelKit.id)
        assertEquals(1, travelKitDrugs.size)

        // Treatment plan still exists
        val plan = treatmentPlanRepository.findByUserIdAndDrugId(user.id, painkiller.id)
        assertNotNull(plan, "Treatment plan should survive drug move")
        assertQty(20.0, plan.plannedAmount)

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
        userRepository.save(anna)
        userRepository.save(bob)

        val medkit = medKitService.createNew(anna.id)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, anna.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)

        val drug = Drug(
            id = UUID.randomUUID(), name = "Medicine X",
            quantity = qty(100.0), quantityUnit = "ml", formType = "liquid",
            category = null, manufacturer = null, country = null,
            description = null, medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()

        // Anna plans 40, Bob plans 30 (total 70, available 30)
        drugService.createPlan(anna.id, drug.id, qty(40.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        entityManager.flush()

        // Anna should be able to increase her plan to 70 (available for her = 100 - 30 (bob) = 70)
        val updated = drugService.changePlan(anna.id, drug.id, qty(70.0))
        assertQty(70.0, updated.plannedAmount)
        entityManager.flush()
        entityManager.clear()
        // Total planned should now be 100 (70 + 30)
        assertQty(100.0, drugRepository.findByIdOrNull(drug.id)?.storedPlannedTotal)

        // Anna should NOT be able to increase to 71 (exceeds available)
        assertFailsWith<PlannedAmountExceedsStock> {
            drugService.changePlan(anna.id, drug.id, qty(71.0))
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
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)

        val medkit = medKitService.createNew(user.id)
        val drug = Drug(
            id = UUID.randomUUID(), name = "Expired Drug",
            quantity = qty(50.0), quantityUnit = "tablets", formType = null,
            category = null, manufacturer = null, country = null,
            description = null, medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()

        // Create treatment plan
        drugService.createPlan(user.id, drug.id, qty(25.0))
        entityManager.flush()
        entityManager.clear()

        // Verify plan exists
        val plan = treatmentPlanRepository.findByUserIdAndDrugId(user.id, drug.id)
        assertNotNull(plan)

        // Delete the drug
        drugService.delete(drug.id, user.id)
        entityManager.flush()
        entityManager.clear()

        // Drug should be gone
        val deletedDrug = drugRepository.findById(drug.id).orElse(null)
        assertNull(deletedDrug)

        // Treatment plan should also be gone (cascade)
        val deletedPlan = treatmentPlanRepository.findByUserIdAndDrugId(user.id, drug.id)
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
        val anna = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}"))
        val bob = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))
        val charlie = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "charlie_${UUID.randomUUID()}"))

        val oldKit = medKitService.createNew(anna.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(oldKit.id, anna.id), bob.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(oldKit.id, anna.id), charlie.id)

        // Setup: Anna and Bob share a New MedKit (Charlie is excluded)
        val newKit = medKitService.createNew(anna.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(newKit.id, anna.id), bob.id)

        // Add drug to old kit
        val drug = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Special Meds", quantity = qty(90.0),
                quantityUnit = "pills", medKit = oldKit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Everyone creates a plan for 30 pills
        drugService.createPlan(anna.id, drug.id, qty(30.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        drugService.createPlan(charlie.id, drug.id, qty(30.0))

        entityManager.flush()
        entityManager.clear()

        // Anna deletes the old kit and migrates to the new kit
        medKitDrugServices.delete(oldKit.id, anna.id, newKit.id)

        entityManager.flush()
        entityManager.clear()

        // Verify: Anna and Bob still have their plans. Charlie's plan was deleted.
        assertNotNull(treatmentPlanRepository.findByUserIdAndDrugId(anna.id, drug.id), "Anna should keep her plan")
        assertNotNull(treatmentPlanRepository.findByUserIdAndDrugId(bob.id, drug.id), "Bob should keep his plan")
        assertNull(
            treatmentPlanRepository.findByUserIdAndDrugId(charlie.id, drug.id),
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
        val anna = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}"))
        val bob = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))

        val kit = medKitService.createNew(anna.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, anna.id), bob.id)

        // Drug has 100 total
        val drug = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Shared Vitamins", quantity = qty(100.0),
                quantityUnit = "pills", medKit = kit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Anna plans 60, Bob plans 40. Total planned = 100.
        drugService.createPlan(anna.id, drug.id, qty(60.0))
        drugService.createPlan(bob.id, drug.id, qty(40.0))

        entityManager.flush()
        entityManager.clear()

        // Bob consumes 50 pills (ignoring his plan limit for emergency)
        // Drug quantity drops to 50.
        // Factor should be: 50 / 100 = 0.5
        drugService.consumeDrug(drug.id, qty(50.0), bob.id)

        entityManager.flush()
        entityManager.clear()

        val annaPlan = treatmentPlanRepository.findByUserIdAndDrugId(anna.id, drug.id)!!
        val bobPlan = treatmentPlanRepository.findByUserIdAndDrugId(bob.id, drug.id)!!

        // Plans should be exactly halved
        assertQty(30.0, annaPlan.plannedAmount, "Anna's plan should scale from 60 to 30")
        assertQty(20.0, bobPlan.plannedAmount, "Bob's plan should scale from 40 to 20")

        println("✅ Story 15 passed: Treatment plans scaled proportionally after heavy consumption")
    }

    /**
     * Story 16: Partial migration prevents orphan removal
     * * Validates: Explicit `targetMedKit.drugs.add(drug)` fix
     */
    @Test
    fun `Story 16 - Moving single drug preserves it from orphan removal`() {
        val user = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}"))

        val sourceKit = medKitService.createNew(user.id)
        val targetKit = medKitService.createNew(user.id)

        val drugToMove = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Moving Pill", quantity = qty(10.0),
                quantityUnit = "pills", medKit = sourceKit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        val drugToStay = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Staying Pill", quantity = qty(10.0),
                quantityUnit = "pills", medKit = sourceKit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        entityManager.flush()
        entityManager.clear()

        // Move ONLY one drug
        medKitDrugServices.moveDrug(drugToMove.id, targetKit.id, user.id)

        entityManager.flush()
        entityManager.clear()

        // Verify it wasn't deleted by orphan removal during the move
        val movedDrug = drugRepository.findById(drugToMove.id).orElse(null)
        assertNotNull(movedDrug, "Moved drug must not be deleted")
        assertEquals(targetKit.id, movedDrug.medKit.id, "Drug should point to new kit")

        val stayingDrug = drugRepository.findById(drugToStay.id).orElse(null)
        assertNotNull(stayingDrug, "Staying drug must not be affected")
        assertEquals(sourceKit.id, stayingDrug.medKit.id)

        println("✅ Story 16 passed: Moving a single drug prevented orphan removal")
    }
}
