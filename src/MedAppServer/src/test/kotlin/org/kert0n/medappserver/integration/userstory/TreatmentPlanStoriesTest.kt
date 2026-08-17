package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.User
import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.kert0n.medappserver.domain.PlannedAmountExceedsStock
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.UserData
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class TreatmentPlanStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper


    @Autowired
    private lateinit var drugRepository: DrugRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService

    /**
     * Story 6: Complex workflow with treatment plans
     * 
     * Validates: Treatment plan creation, intake recording, planned quantity tracking
     */
    @Test
    fun `Story 6 - User creates treatment plan and records intakes`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)

        val medkit = medKitService.createNew(userData.id)
        val drugData = Drug(
            id = UUID.randomUUID(),
            name = "Treatment Drug",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Create treatment plan for 30 tablets
        val plan = drugService.createPlan(userId = userData.id, drugData.id, qty(30.0))
        assertNotNull(plan)
        entityManager.flush()

        // Verify plan was created
        val createdPlan = dbHelper.userPlan(userData.id, drugData.id)
        assertNotNull(createdPlan, "Plan should be created")
        assertQty(30.0, createdPlan, "Planned amount should be 30")

        // Record some intakes
        drugService.recordIntake(userData.id, drugData.id, qty(5.0))
        drugService.recordIntake(userData.id, drugData.id, qty(5.0))
        entityManager.flush()
        entityManager.clear()

        // Verify drug quantity decreased
        val updatedDrug = drugService.findById(drugData.id)
        assertNotNull(updatedDrug)
        assertQty(90.0, updatedDrug.quantity, "Drug quantity should be 90 after 10 consumed")

        println("✅ Story 6 passed: Treatment plan and intakes work correctly")
    }

    /**
     * Story 7: Multiple users share a medkit and create separate treatment plans for the same drug
     * 
     * Validates: Multi-user treatment plans, planned quantity accounting, fair sharing
     */
    @Test
    fun `Story 7 - Multiple users create treatment plans on shared drug`() {
        // Setup: Anna and Bob share a medkit with 100 tablets of Vitamin C
        val anna = User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}")
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.createNew(anna.id)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, anna.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)

        val vitaminC = Drug(
            id = UUID.randomUUID(),
            name = "Vitamin C",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(vitaminC)
        entityManager.flush()

        // Anna creates a treatment plan for 40 tablets
        drugService.createPlan(anna.id, vitaminC.id, qty(40.0))
        entityManager.flush()

        // Bob creates a treatment plan for 50 tablets (should succeed: 100 - 40 = 60 available)
        drugService.createPlan(bob.id, vitaminC.id, qty(50.0))
        entityManager.flush()
        entityManager.clear()
        // Total planned = 90, should match sumPlannedAmount
        assertQty(90.0, dbHelper.totalPlanned(vitaminC.id), "Total planned should be 90")

        // Verify each user has their own plan
        val annaPlan = dbHelper.userPlan(anna.id, vitaminC.id)
        val bobPlan = dbHelper.userPlan(bob.id, vitaminC.id)
        assertNotNull(annaPlan)
        assertNotNull(bobPlan)
        assertQty(40.0, annaPlan)
        assertQty(50.0, bobPlan)

        println("✅ Story 7 passed: Multiple users created treatment plans on shared drug")
    }

    /**
     * Story 8: Drug quantity reduction cascades to treatment plans
     * 
     * Validates: plan reconciliation, proportional reduction of plans
     */
    @Test
    fun `Story 8 - Reducing drug quantity adjusts treatment plans proportionally`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)
        val medkit = medKitService.createNew(userData.id)

        val drugData = Drug(
            id = UUID.randomUUID(),
            name = "Paracetamol",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Create plan for 80 tablets
        drugService.createPlan(userData.id, drugData.id, qty(80.0))
        entityManager.flush()
        entityManager.clear()

        // Consume 50 tablets (drug goes to 50, but plan is 80 > 50)
        // reconciliation should scale the plan down
        drugService.consume(drugData.id, qty(50.0), userData.id)
        entityManager.flush()
        entityManager.clear()

        val updatedDrug = drugService.findById(drugData.id)
        assertNotNull(updatedDrug)
        assertQty(50.0, updatedDrug.quantity)

        // Plan should be reduced proportionally: 80 * (50/80) = 50
        val updatedPlan = dbHelper.userPlan(userData.id, drugData.id)
        assertNotNull(updatedPlan)
        assertTrue(updatedPlan <= qty(50.0), "Plan should be reduced to fit available quantity")

        println("✅ Story 8 passed: Drug quantity reduction cascaded to treatment plans")
    }

    /**
     * Story 9: Cannot create treatment plan exceeding available quantity
     * 
     * Validates: Planned quantity validation, error handling
     */
    @Test
    fun `Story 9 - Cannot over-plan drug quantity`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)
        val medkit = medKitService.createNew(userData.id)

        val drugData = Drug(
            id = UUID.randomUUID(),
            name = "Ibuprofen",
            quantity = Quantity(qty(50.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Try to create a plan for 60 tablets when only 50 available
        assertFailsWith<PlannedAmountExceedsStock> {
            drugService.createPlan(userData.id, drugData.id, qty(60.0))
        }

        // Create a plan for 30
        drugService.createPlan(userData.id, drugData.id, qty(30.0))
        entityManager.flush()

        // Another user tries to plan 25 (only 20 available: 50 - 30 = 20)
        val userData2 = User(id = UUID.randomUUID(), hashedKey = "user2_${UUID.randomUUID()}")
        dbHelper.insert(userData2)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, userData.id)
        medKitService.joinMedKitByKey(shareKey, userData2.id)
        entityManager.flush()
        entityManager.clear()
        assertFailsWith<PlannedAmountExceedsStock> {
            drugService.createPlan(userData2.id, drugData.id, qty(25.0))
        }

        // But 20 should work
        drugService.createPlan(userData2.id, drugData.id, qty(20.0))
        entityManager.flush()
        entityManager.clear()
        assertQty(50.0, dbHelper.totalPlanned(drugData.id))

        println("✅ Story 9 passed: Cannot over-plan drug quantity")
    }

    /**
     * Story 10: Complete family medkit lifecycle
     * 
     * Validates: Full end-to-end workflow from creation to cleanup
     */
    @Test
    fun `Story 10 - Complete family medkit lifecycle`() {
        // Mom creates a family medkit
        val mom = User(id = UUID.randomUUID(), hashedKey = "mom_${UUID.randomUUID()}")
        val dad = User(id = UUID.randomUUID(), hashedKey = "dad_${UUID.randomUUID()}")
        val child = User(id = UUID.randomUUID(), hashedKey = "child_${UUID.randomUUID()}")
        dbHelper.insert(mom)
        dbHelper.insert(dad)
        dbHelper.insert(child)
        entityManager.flush()

        val familyKit = medKitService.createNew(mom.id)
        val dadKey = medKitService.generateMedKitShareKey(familyKit.id, mom.id)
        medKitService.joinMedKitByKey(dadKey, dad.id)
        val childKey = medKitService.generateMedKitShareKey(familyKit.id, mom.id)
        medKitService.joinMedKitByKey(childKey, child.id)
        entityManager.flush()

        // Add family medications
        val aspirin = Drug(
            id = UUID.randomUUID(), name = "Children's Aspirin",
            quantity = Quantity(qty(200.0), dbHelper.unit()),
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKitId = familyKit.id
        )
        val vitamins = Drug(
            id = UUID.randomUUID(), name = "Multivitamins",
            quantity = Quantity(qty(90.0), dbHelper.unit()),
            category = "supplement", manufacturer = null, country = null,
            description = null, medKitId = familyKit.id
        )
        dbHelper.insert(aspirin)
        dbHelper.insert(vitamins)
        entityManager.flush()

        // Everyone gets treatment plans for vitamins: 30 each
        drugService.createPlan(mom.id, vitamins.id, qty(30.0))
        drugService.createPlan(dad.id, vitamins.id, qty(30.0))
        drugService.createPlan(child.id, vitamins.id, qty(30.0))
        entityManager.flush()
        entityManager.clear()
        // Total planned = 90 (full supply)
        assertQty(90.0, dbHelper.totalPlanned(vitamins.id))

        // Everyone takes their daily vitamin
        drugService.recordIntake(mom.id, vitamins.id, qty(1.0))
        entityManager.flush()
        drugService.recordIntake(dad.id, vitamins.id, qty(1.0))
        entityManager.flush()
        drugService.recordIntake(child.id, vitamins.id, qty(1.0))
        entityManager.flush()
        entityManager.clear()

        // Check vitamins after 1 day
        val updatedVitamins = drugService.findById(vitamins.id)
        assertNotNull(updatedVitamins)
        assertQty(87.0, updatedVitamins.quantity, "Should be 90 - 3 = 87")

        // 3 users in the medkit
        val medkit = medKitStore.findById(familyKit.id)
        assertNotNull(medkit)
        assertEquals(3, medkit.members.size)

        // Child leaves the medkit
        medKitDrugOrchestrator.leaveMedKit(familyKit.id, child.id)
        entityManager.flush()
        entityManager.clear()

        // Medkit still has mom and dad
        val updatedKit = medKitStore.findById(familyKit.id)
        assertNotNull(updatedKit)
        assertEquals(2, updatedKit.members.size)

        println("✅ Story 10 passed: Complete family medkit lifecycle")
    }
}
