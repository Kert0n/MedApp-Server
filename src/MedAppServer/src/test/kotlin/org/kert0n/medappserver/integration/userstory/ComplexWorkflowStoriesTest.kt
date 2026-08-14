package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.kert0n.medappserver.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kert0n.medappserver.controller.DrugCreateDTO
import org.kert0n.medappserver.controller.DrugUpdateDTO
import org.kert0n.medappserver.controller.UsingCreateDTO
import org.kert0n.medappserver.controller.UsingUpdateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@PostgresIntegrationTest
@Transactional
class ComplexWorkflowStoriesTest {

    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

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
    private lateinit var usingService: UsingService

    /**
     * Story 17: The Roommate Saga (The Ultimate Stress Test)
     * * Validates:
     * - Multi-user sharing and permissions
     * - Proportional quantity reduction of Usings during heavy consumption
     * - Security stripping of Usings during single-drug moves
     * - Security stripping of Usings during full kit migrations
     * - Orphan removal prevention during migrations
     * - Auto-deletion of MedKits when empty
     * - JPA L1 Cache integrity across complex interwoven workflows
     */
    @Test
    fun `Story 17 - The Roommate Saga complex interwoven workflow`() {
        // ==========================================
        // PHASE 1: Setup and Sharing
        // ==========================================
        val alice = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "alice_${UUID.randomUUID()}"))
        val bob = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))
        val charlie = userRepository.save(User(id = UUID.randomUUID(), hashedKey = "charlie_${UUID.randomUUID()}"))

        val homeKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(homeKit.id, alice.id), bob.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(homeKit.id, alice.id), charlie.id)

        val allergyMeds = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Allergy Meds", quantity = qty(60.0),
                quantityUnit = "pills", medKit = homeKit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )
        val painkillers = drugRepository.save(
            Drug(
                id = UUID.randomUUID(), name = "Painkillers", quantity = qty(100.0),
                quantityUnit = "pills", medKit = homeKit, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Emulate end of HTTP request
        entityManager.flush()
        entityManager.clear()

        // ==========================================
        // PHASE 2: Everyone makes Treatment Plans
        // ==========================================
        // Allergy Meds: 60 total. Alice (20), Bob (20), Charlie (20) = 60 planned.
        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(allergyMeds.id, qty(20.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(allergyMeds.id, qty(20.0)))
        usingService.createTreatmentPlan(charlie.id, UsingCreateDTO(allergyMeds.id, qty(20.0)))

        // Painkillers: 100 total. Bob plans 30, Charlie plans 30.
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(painkillers.id, qty(30.0)))
        usingService.createTreatmentPlan(charlie.id, UsingCreateDTO(painkillers.id, qty(30.0)))

        entityManager.flush()
        entityManager.clear()

        // ==========================================
        // PHASE 3: Heavy Consumption & Auto-Scaling
        // ==========================================
        // Bob consumes 30 Allergy Meds. Stock drops from 60 to 30.
        // Total planned was 60. Stock is now 30. Scale factor = 30/60 = 0.5.
        // All plans (20) should auto-scale down to 10.
        drugService.consumeDrug(allergyMeds.id, qty(30.0), bob.id)

        entityManager.flush()
        entityManager.clear()

        val updatedAllergyMeds = drugRepository.findById(allergyMeds.id).get()
        assertQty(30.0, updatedAllergyMeds.quantity, "Stock should be 30")

        val aliceAllergyPlan = usingRepository.findByUserIdAndDrugId(alice.id, allergyMeds.id)!!
        assertQty(10.0, aliceAllergyPlan.plannedAmount, "Alice's plan should auto-scale to 10")

        // ==========================================
        // PHASE 4: Single Drug Move (Security Audit)
        // ==========================================
        // Alice makes a private travel kit and takes the Painkillers.
        val travelKit = medKitService.createNew(alice.id)

        entityManager.flush()
        entityManager.clear()

        medKitDrugServices.moveDrug(painkillers.id, travelKit.id, alice.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Bob and Charlie lost their Painkiller plans because they can't see the Travel Kit
        assertNull(usingRepository.findByUserIdAndDrugId(bob.id, painkillers.id), "Bob's plan must be deleted")
        assertNull(usingRepository.findByUserIdAndDrugId(charlie.id, painkillers.id), "Charlie's plan must be deleted")

        val movedPainkillers = drugRepository.findById(painkillers.id).get()
        assertEquals(travelKit.id, movedPainkillers.medKit.id, "Drug successfully moved")

        // ==========================================
        // PHASE 5: Kill & Migrate (The Final Boss)
        // ==========================================
        // Alice deletes Home Kit. She moves remaining Allergy Meds to a new "Duo Kit" with just Bob.
        val duoKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(duoKit.id, alice.id), bob.id)

        entityManager.flush()
        entityManager.clear()

        // Perform the complex deletion migration
        medKitDrugServices.delete(homeKit.id, alice.id, duoKit.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Home Kit is dead
        assertNull(medKitRepository.findByIdOrNull(homeKit.id), "Home kit must be completely deleted")

        // Verify Allergy Meds moved safely without orphan removal
        val migratedAllergyMeds = drugRepository.findById(allergyMeds.id).orElse(null)
        assertNotNull(migratedAllergyMeds, "Allergy meds must survive the migration")
        assertEquals(duoKit.id, migratedAllergyMeds.medKit.id, "Allergy meds are in Duo Kit")

        // Verify Charlie's Allergy Meds plan was stripped because he isn't in Duo Kit
        assertNull(
            usingRepository.findByUserIdAndDrugId(charlie.id, allergyMeds.id),
            "Charlie's last plan must be deleted"
        )

        // Verify Alice and Bob kept their 10.0 scaled plans
        val finalAlicePlan = usingRepository.findByUserIdAndDrugId(alice.id, allergyMeds.id)!!
        assertQty(10.0, finalAlicePlan.plannedAmount, "Alice kept her plan through migration")

        // ==========================================
        // PHASE 6: Last User Standing Auto-Cleanup
        // ==========================================
        // Bob leaves Duo Kit
        medKitDrugServices.removeUserFromMedKit(duoKit.id, bob.id)

        entityManager.flush()
        entityManager.clear()

        val duoKitCheck1 = medKitRepository.findById(duoKit.id).get()
        assertEquals(1, duoKitCheck1.users.size, "Only Alice remains")

        // Alice leaves Duo Kit. Because she is the last user, the kit should auto-delete.
        // (Using medKitService directly as medKitDrugServices might check for users first)
        val aliceFresh = userRepository.findById(alice.id).get()
        medKitService.removeUserFromMedKit(duoKitCheck1, aliceFresh)

        entityManager.flush()
        entityManager.clear()

        assertNull(medKitRepository.findByIdOrNull(duoKit.id), "Duo kit must auto-delete when last user leaves")
        assertNull(
            drugRepository.findByIdOrNull(allergyMeds.id),
            "Cascade should kill the drugs inside the abandoned kit"
        )

        println("✅ Story 17 passed: The Roommate Saga completed without a single JPA integrity violation")
    }

    @Test
    fun `Story 18 - Full Lifecycle Alterations, Movement, and Deletion`() {
        // ── Setup ──
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")

        val sourceKit = medKitService.createNew(alice.id)
        val targetKit = medKitService.createNew(alice.id) // Only Alice has access to this one
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(sourceKit.id, alice.id), bob.id)

        // Alice adds 100 tablets to sourceKit
        val createDrugDto = DrugCreateDTO(
            name = "LifePill", quantity = qty(100.0), quantityUnit = "tablets",
            medKitId = sourceKit.id, formType = null, category = null,
            manufacturer = null, country = null, description = null
        )
        val drug = medKitDrugServices.createDrugInMedkit(createDrugDto, alice.id)
        dbHelper.flushAndClear()

        // Alice and Bob create treatment plans (40 each, total 80)
        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(40.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        // ── Phase 1: Alter Using ──
        // Bob increases his plan from 40 to 60.
        // Allowed because 100 stock - 40 Alice = 60 available.
        usingService.updateTreatmentPlan(bob.id, drug.id, UsingUpdateDTO(plannedAmount = qty(60.0)))
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.userPlan(bob.id, drug.id), "Bob's plan updated to 60")
        assertQty(40.0, dbHelper.userPlan(alice.id, drug.id), "Alice's plan unchanged at 40")

        // ── Phase 2: Alter Drug (The Spill) ──
        // Alice updates the drug quantity from 100 to 50.
        // This MUST trigger `handleQuantityReduction`. Factor = 50 / 100 = 0.5.
        val updateDrugDto = DrugUpdateDTO(quantity = qty(50.0))
        drugService.update(drug.id, updateDrugDto, alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id), "Drug quantity updated to 50")
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id), "Alice scaled down (40 -> 20)")
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id), "Bob scaled down (60 -> 30)")

        // ── Phase 3: Move Drug ──
        // Alice moves the drug to targetKit (where Bob has no access).
        medKitDrugServices.moveDrug(drug.id, targetKit.id, alice.id)
        dbHelper.flushAndClear()

        val movedDrug = drugService.findById(drug.id)
        assertEquals(targetKit.id, movedDrug.medKit.id, "Drug successfully moved to targetKit")

        // The ultimate security check: Bob's plan must be gone
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan MUST be stripped due to lost access")
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id), "Alice's plan remains intact")

        // ── Phase 4: Privacy-by-Default Deletion ──
        // Alice deletes the drug completely.
        drugService.delete(drug.id, alice.id)
        dbHelper.flushAndClear()

        // Verify absolute destruction
        assertNull(dbHelper.drugQuantity(drug.id), "Drug record completely purged")
        assertNull(dbHelper.userPlan(alice.id, drug.id), "Alice's plan completely purged along with the drug")

        println("✅ Story 18 passed: Updates, dynamic scaling, access stripping on move, and total deletion worked perfectly.")
    }

    private fun createTestUser(name: String): User {
        // Using repository directly to bypass any complex auth logic in UserService if necessary
        return userRepository.save(User(id = UUID.randomUUID(), hashedKey = name))
    }

    @Test
    fun `Story 19 - Roommate can move drug even without personal treatment plan`() {
        // SETUP: Alice owns a kit, Bob is a roommate
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")

        val kitA = medKitService.createNew(alice.id)
        val shareKey = medKitService.generateMedKitShareKey(kitA.id, alice.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)

        // Alice creates a drug
        val drug = drugService.create(DrugCreateDTO("Shared Meds", qty(10.0), "pcs", kitA.id), kitA, alice.id)

        // Bob creates a private kit
        val kitB = medKitService.createNew(bob.id)

        // ACT: Bob moves the drug to his private kit
        // This fails if the query uses an INNER JOIN on the 'usings' table
        assertDoesNotThrow {
            medKitDrugServices.moveDrug(drug.id, kitB.id, bob.id)
        }

        // VERIFY: Drug moved
        val updatedDrug = drugRepository.findById(drug.id).get()
        assertEquals(kitB.id, updatedDrug.medKit.id)
    }

    @Test
    fun `Verify movement strips unauthorized usings`() {
        // SETUP: Shared kit with Alice and Bob
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")
        val kitA = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kitA.id, alice.id), bob.id)

        val drug = drugService.create(DrugCreateDTO("Audit Meds", qty(10.0), "pcs", kitA.id), kitA, alice.id)

        // Both have plans
        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(5.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(2.0)))

        // Alice has a private kit (Bob is NOT in this one)
        val kitB = medKitService.createNew(alice.id)
        entityManager.flush()
        entityManager.clear()
        // ACT: Move drug to private kit
        medKitDrugServices.moveDrug(drug.id, kitB.id, alice.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Bob's plan is purged, Alice's remains
        val alicePlan = usingRepository.findAllByUserIdWithDrug(alice.id).find { it.drug.id == drug.id }
        val bobPlan = usingRepository.findAllByUserIdWithDrug(bob.id).find { it.drug.id == drug.id }
        assertNotNull(alicePlan, "Alice should keep her plan")
        assertNull(bobPlan, "Bob's plan must be deleted because he lost access to the drug")
    }

    @Test
    fun `Verify drug migration during MedKit deletion`() {
        // SETUP: Alice has Kit A and Kit B
        val alice = createTestUser("alice")
        val kitA = medKitService.createNew(alice.id)
        val kitB = medKitService.createNew(alice.id)
        entityManager.flush()
        entityManager.clear()
        val drug =
            medKitDrugServices.createDrugInMedkit(DrugCreateDTO("Migrating Meds", qty(10.0), "pcs", kitA.id), alice.id)

        // ACT: Delete Kit A and migrate drugs to Kit B
        entityManager.flush()
        entityManager.clear()
        medKitDrugServices.delete(kitA.id, alice.id, kitB.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Kit A is gone, but the drug survives in Kit B
        val survivingDrug = drugRepository.findById(drug.id).orElse(null)

        assertNotNull(survivingDrug, "Drug should not have been deleted")
        assertEquals(kitB.id, survivingDrug.medKit.id, "Drug should be re-parented to Kit B")
    }
}
