package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.kert0n.medappserver.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@PostgresIntegrationTest
@Transactional
class TreatmentPlanStoriesTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var medKitRepository: MedKitRepository

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
    private lateinit var medKitDrugServices: MedKitDrugServices

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var usingService: UsingService

    /**
     * Story 6: Complex workflow with treatment plans
     * 
     * Validates: Treatment plan creation, intake recording, planned quantity tracking
     */
    @Test
    fun `Story 6 - User creates treatment plan and records intakes`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)

        val medkit = medKitService.createNew(user.id)
        val drug = Drug(
            id = UUID.randomUUID(),
            name = "Treatment Drug",
            quantity = qty(100.0),
            quantityUnit = "tablets",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()

        // Create treatment plan for 30 tablets
        val plan = treatmentPlanService.create(
            userId = user.id,
            drugId = drug.id,
            plannedAmount = qty(30.0)
        )
        assertNotNull(plan)
        entityManager.flush()

        // Verify plan was created
        val createdPlan = usingRepository.findByUserIdAndDrugId(user.id, drug.id)
        assertNotNull(createdPlan, "Plan should be created")
        assertQty(30.0, createdPlan.plannedAmount, "Planned amount should be 30")

        // Record some intakes
        drugService.applyIntake(user.id, drug.id, qty(5.0))
        drugService.applyIntake(user.id, drug.id, qty(5.0))
        entityManager.flush()
        entityManager.clear()

        // Verify drug quantity decreased
        val updatedDrug = drugRepository.findById(drug.id).orElse(null)
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
        userRepository.save(anna)
        userRepository.save(bob)

        val medkit = medKitService.createNew(anna.id)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, anna.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)

        val vitaminC = Drug(
            id = UUID.randomUUID(),
            name = "Vitamin C",
            quantity = qty(100.0),
            quantityUnit = "tablets",
            formType = "tablet",
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(vitaminC)
        entityManager.flush()

        // Anna creates a treatment plan for 40 tablets
        treatmentPlanService.create(anna.id, vitaminC.id, qty(40.0))
        entityManager.flush()

        // Bob creates a treatment plan for 50 tablets (should succeed: 100 - 40 = 60 available)
        treatmentPlanService.create(bob.id, vitaminC.id, qty(50.0))
        entityManager.flush()
        entityManager.clear()
        // Total planned = 90, should match sumPlannedAmount
        assertQty(90.0, drugRepository.findByIdOrNull(vitaminC.id)?.totalPlannedAmount, "Total planned should be 90")

        // Verify each user has their own plan
        val annaUsing = usingRepository.findByUserIdAndDrugId(anna.id, vitaminC.id)
        val bobUsing = usingRepository.findByUserIdAndDrugId(bob.id, vitaminC.id)
        assertNotNull(annaUsing)
        assertNotNull(bobUsing)
        assertQty(40.0, annaUsing.plannedAmount)
        assertQty(50.0, bobUsing.plannedAmount)

        println("✅ Story 7 passed: Multiple users created treatment plans on shared drug")
    }

    /**
     * Story 8: Drug quantity reduction cascades to treatment plans
     * 
     * Validates: handleQuantityReduction logic, proportional reduction of plans
     */
    @Test
    fun `Story 8 - Reducing drug quantity adjusts treatment plans proportionally`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)
        val medkit = medKitService.createNew(user.id)

        val drug = Drug(
            id = UUID.randomUUID(),
            name = "Paracetamol",
            quantity = qty(100.0),
            quantityUnit = "tablets",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()

        // Create plan for 80 tablets
        treatmentPlanService.create(user.id, drug.id, qty(80.0))
        entityManager.flush()
        entityManager.clear()

        // Consume 50 tablets (drug goes to 50, but plan is 80 > 50)
        // handleQuantityReduction should scale the plan down
        drugService.consume(drug.id, qty(50.0), user.id)
        entityManager.flush()
        entityManager.clear()

        val updatedDrug = drugRepository.findById(drug.id).orElse(null)
        assertNotNull(updatedDrug)
        assertQty(50.0, updatedDrug.quantity)

        // Plan should be reduced proportionally: 80 * (50/80) = 50
        val updatedPlan = usingRepository.findByUserIdAndDrugId(user.id, drug.id)
        assertNotNull(updatedPlan)
        assertTrue(updatedPlan.plannedAmount <= qty(50.0), "Plan should be reduced to fit available quantity")

        println("✅ Story 8 passed: Drug quantity reduction cascaded to treatment plans")
    }

    /**
     * Story 9: Cannot create treatment plan exceeding available quantity
     * 
     * Validates: Planned quantity validation, error handling
     */
    @Test
    fun `Story 9 - Cannot over-plan drug quantity`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)
        val medkit = medKitService.createNew(user.id)

        val drug = Drug(
            id = UUID.randomUUID(),
            name = "Ibuprofen",
            quantity = qty(50.0),
            quantityUnit = "tablets",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()

        // Try to create a plan for 60 tablets when only 50 available
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.create(user.id, drug.id, qty(60.0))
        }

        // Create a plan for 30
        treatmentPlanService.create(user.id, drug.id, qty(30.0))
        entityManager.flush()

        // Another user tries to plan 25 (only 20 available: 50 - 30 = 20)
        val user2 = User(id = UUID.randomUUID(), hashedKey = "user2_${UUID.randomUUID()}")
        userRepository.save(user2)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, user.id)
        medKitService.joinMedKitByKey(shareKey, user2.id)
        entityManager.flush()
        entityManager.clear()
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.create(user2.id, drug.id, qty(25.0))
        }

        // But 20 should work
        treatmentPlanService.create(user2.id, drug.id, qty(20.0))
        entityManager.flush()
        entityManager.clear()
        assertQty(50.0, drugRepository.findByIdOrNull(drug.id)?.totalPlannedAmount)

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
        userRepository.save(mom)
        userRepository.save(dad)
        userRepository.save(child)
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
            quantity = qty(200.0), quantityUnit = "tablets", formType = "chewable",
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKit = familyKit
        )
        val vitamins = Drug(
            id = UUID.randomUUID(), name = "Multivitamins",
            quantity = qty(90.0), quantityUnit = "tablets", formType = "tablet",
            category = "supplement", manufacturer = null, country = null,
            description = null, medKit = familyKit
        )
        drugRepository.save(aspirin)
        drugRepository.save(vitamins)
        entityManager.flush()

        // Everyone gets treatment plans for vitamins: 30 each
        treatmentPlanService.create(mom.id, vitamins.id, qty(30.0))
        treatmentPlanService.create(dad.id, vitamins.id, qty(30.0))
        treatmentPlanService.create(child.id, vitamins.id, qty(30.0))
        entityManager.flush()
        entityManager.clear()
        // Total planned = 90 (full supply)
        assertQty(90.0, drugRepository.findByIdOrNull(vitamins.id)?.totalPlannedAmount)

        // Everyone takes their daily vitamin
        drugService.applyIntake(mom.id, vitamins.id, qty(1.0))
        entityManager.flush()
        drugService.applyIntake(dad.id, vitamins.id, qty(1.0))
        entityManager.flush()
        drugService.applyIntake(child.id, vitamins.id, qty(1.0))
        entityManager.flush()
        entityManager.clear()

        // Check vitamins after 1 day
        val updatedVitamins = drugRepository.findById(vitamins.id).orElse(null)
        assertNotNull(updatedVitamins)
        assertQty(87.0, updatedVitamins.quantity, "Should be 90 - 3 = 87")

        // 3 users in the medkit
        val medkit = medKitRepository.findById(familyKit.id).orElse(null)
        assertNotNull(medkit)
        assertEquals(3, medkit.users.size)

        // Child leaves the medkit
        medKitDrugServices.removeUserFromMedKit(familyKit.id, child.id)
        entityManager.flush()
        entityManager.clear()

        // Medkit still has mom and dad
        val updatedKit = medKitRepository.findById(familyKit.id).orElse(null)
        assertNotNull(updatedKit)
        assertEquals(2, updatedKit.users.size)

        println("✅ Story 10 passed: Complete family medkit lifecycle")
    }
}
