package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.user.User
import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugCreateRequest
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
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class ComplexWorkflowStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

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
    private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService

    /**
     * Story 17: The Roommate Saga (The Ultimate Stress Test)
     * * Validates:
     * - Multi-user sharing and permissions
     * - Proportional quantity reduction of treatment plans during heavy consumption
     * - Security stripping of treatment plans during single-drug moves
     * - Security stripping of treatment plans during full kit migrations
     * - Orphan removal prevention during migrations
     * - Auto-deletion of MedKits when empty
     * - JPA L1 Cache integrity across complex interwoven workflows
     */
    @Test
    fun `Story 17 - The Roommate Saga complex interwoven workflow`() {
        // ==========================================
        // PHASE 1: Setup and Sharing
        // ==========================================
        val alice = dbHelper.insert(User.register(id = UUID.randomUUID(), hashedKey = "alice_${UUID.randomUUID()}"))
        val bob = dbHelper.insert(User.register(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))
        val charlie = dbHelper.insert(User.register(id = UUID.randomUUID(), hashedKey = "charlie_${UUID.randomUUID()}"))

        val homeKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(homeKit.id, alice.id), bob.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(homeKit.id, alice.id), charlie.id)

        val allergyMeds = dbHelper.insert(
            Drug.create(
                id = UUID.randomUUID(), name = "Allergy Meds", quantity = qty(60.0),
                quantityUnit = "pills", medKitId = homeKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )
        val painkillers = dbHelper.insert(
            Drug.create(
                id = UUID.randomUUID(), name = "Painkillers", quantity = qty(100.0),
                quantityUnit = "pills", medKitId = homeKit.id, formType = null,
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
        drugService.createPlan(alice.id, allergyMeds.id, qty(20.0))
        drugService.createPlan(bob.id, allergyMeds.id, qty(20.0))
        drugService.createPlan(charlie.id, allergyMeds.id, qty(20.0))

        // Painkillers: 100 total. Bob plans 30, Charlie plans 30.
        drugService.createPlan(bob.id, painkillers.id, qty(30.0))
        drugService.createPlan(charlie.id, painkillers.id, qty(30.0))

        entityManager.flush()
        entityManager.clear()

        // ==========================================
        // PHASE 3: Heavy Consumption & Auto-Scaling
        // ==========================================
        // Bob consumes 30 Allergy Meds. Stock drops from 60 to 30.
        // Total planned was 60. Stock is now 30. Scale factor = 30/60 = 0.5.
        // All plans (20) should auto-scale down to 10.
        drugService.consume(allergyMeds.id, qty(30.0), bob.id)

        entityManager.flush()
        entityManager.clear()

        val updatedAllergyMeds = drugService.requireById(allergyMeds.id)
        assertQty(30.0, updatedAllergyMeds.quantity, "Stock should be 30")

        val aliceAllergyPlan = dbHelper.userPlan(alice.id, allergyMeds.id)!!
        assertQty(10.0, aliceAllergyPlan, "Alice's plan should auto-scale to 10")

        // ==========================================
        // PHASE 4: Single Drug Move (Security Audit)
        // ==========================================
        // Alice makes a private travel kit and takes the Painkillers.
        val travelKit = medKitService.createNew(alice.id)

        entityManager.flush()
        entityManager.clear()

        medKitDrugOrchestrator.moveDrug(painkillers.id, travelKit.id, alice.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Bob and Charlie lost their Painkiller plans because they can't see the Travel Kit
        assertNull(dbHelper.userPlan(bob.id, painkillers.id), "Bob's plan must be deleted")
        assertNull(dbHelper.userPlan(charlie.id, painkillers.id), "Charlie's plan must be deleted")

        val movedPainkillers = drugService.requireById(painkillers.id)
        assertEquals(travelKit.id, movedPainkillers.medKitId, "Drug successfully moved")

        // ==========================================
        // PHASE 5: Kill & Migrate (The Final Boss)
        // ==========================================
        // Alice deletes Home Kit. She moves remaining Allergy Meds to a new "Duo Kit" with just Bob.
        val duoKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(duoKit.id, alice.id), bob.id)

        entityManager.flush()
        entityManager.clear()

        // Perform the complex deletion migration
        medKitDrugOrchestrator.delete(homeKit.id, alice.id, duoKit.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Home Kit is dead
        assertNull(medKitStore.findById(homeKit.id), "Home kit must be completely deleted")

        // Verify Allergy Meds moved safely without orphan removal
        val migratedAllergyMeds = drugService.findById(allergyMeds.id)
        assertNotNull(migratedAllergyMeds, "Allergy meds must survive the migration")
        assertEquals(duoKit.id, migratedAllergyMeds.medKitId, "Allergy meds are in Duo Kit")

        // Verify Charlie's Allergy Meds plan was stripped because he isn't in Duo Kit
        assertNull(
            dbHelper.userPlan(charlie.id, allergyMeds.id),
            "Charlie's last plan must be deleted"
        )

        // Verify Alice and Bob kept their 10.0 scaled plans
        val finalAlicePlan = dbHelper.userPlan(alice.id, allergyMeds.id)!!
        assertQty(10.0, finalAlicePlan, "Alice kept her plan through migration")

        // ==========================================
        // PHASE 6: Last User Standing Auto-Cleanup
        // ==========================================
        // Bob leaves Duo Kit
        medKitDrugOrchestrator.leaveMedKit(duoKit.id, bob.id)

        entityManager.flush()
        entityManager.clear()

        val duoKitCheck1 = medKitStore.findById(duoKit.id)!!
        assertEquals(1, duoKitCheck1.members.size, "Only Alice remains")

        // Alice leaves Duo Kit. Because she is the last user, the kit should auto-delete.
        // (Using medKitService directly as medKitDrugOrchestrator might check for users first)
        medKitService.removeUserFromMedKit(duoKitCheck1.id, alice.id)

        entityManager.flush()
        entityManager.clear()

        assertNull(medKitStore.findById(duoKit.id), "Duo kit must auto-delete when last user leaves")
        assertNull(
            drugService.findById(allergyMeds.id),
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
        val createDrugDto = DrugCreateRequest(
            name = "LifePill", quantity = qty(100.0), quantityUnit = "tablets", formType = null, category = null,
            manufacturer = null, country = null, description = null
        )
        val drug = medKitDrugOrchestrator.createDrugInMedKit(sourceKit.id, createDrugDto, alice.id)
        dbHelper.flushAndClear()

        // Alice and Bob create treatment plans (40 each, total 80)
        drugService.createPlan(alice.id, drug.id, qty(40.0))
        drugService.createPlan(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // ── Phase 1: Alter treatment plan ──
        // Bob increases his plan from 40 to 60.
        // Allowed because 100 stock - 40 Alice = 60 available.
        drugService.changePlan(bob.id, drug.id, qty(60.0))
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.userPlan(bob.id, drug.id)!!, "Bob's plan updated to 60")
        assertQty(40.0, dbHelper.userPlan(alice.id, drug.id)!!, "Alice's plan unchanged at 40")

        // ── Phase 2: Alter Drug (The Spill) ──
        // Alice spills half the bottle: 50 of the 100 are gone. A spill is consumption
        // outside anyone's plan, so it goes through the consumption path — PATCH may only
        // raise the stock. Plans must scale down by 50 / 100 = 0.5.
        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id)!!, "Drug quantity updated to 50")
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id)!!, "Alice scaled down (40 -> 20)")
        assertQty(30.0, dbHelper.userPlan(bob.id, drug.id)!!, "Bob scaled down (60 -> 30)")

        // ── Phase 3: Move Drug ──
        // Alice moves the drug to targetKit (where Bob has no access).
        medKitDrugOrchestrator.moveDrug(drug.id, targetKit.id, alice.id)
        dbHelper.flushAndClear()

        val movedDrug = drugService.requireById(drug.id)
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug successfully moved to targetKit")

        // The ultimate security check: Bob's plan must be gone
        assertNull(dbHelper.userPlan(bob.id, drug.id), "Bob's plan MUST be stripped due to lost access")
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id)!!, "Alice's plan remains intact")

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
        return dbHelper.insert(User.register(id = UUID.randomUUID(), hashedKey = name))
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
        val drug = drugService.create(DrugCreateRequest("Shared Meds", qty(10.0), "pcs"), kitA.id, alice.id)

        // Bob creates a private kit
        val kitB = medKitService.createNew(bob.id)

        // ACT: Bob moves the drug to his private kit
        // This fails if the query uses an INNER JOIN on the 'usings' table
        assertDoesNotThrow {
            medKitDrugOrchestrator.moveDrug(drug.id, kitB.id, bob.id)
        }

        // VERIFY: Drug moved
        val updatedDrug = drugService.requireById(drug.id)
        assertEquals(kitB.id, updatedDrug.medKitId)
    }

    @Test
    fun `Verify movement strips unauthorized treatment plans`() {
        // SETUP: Shared kit with Alice and Bob
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")
        val kitA = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kitA.id, alice.id), bob.id)

        val drug = drugService.create(DrugCreateRequest("Audit Meds", qty(10.0), "pcs"), kitA.id, alice.id)

        // Both have plans
        drugService.createPlan(alice.id, drug.id, qty(5.0))
        drugService.createPlan(bob.id, drug.id, qty(2.0))

        // Alice has a private kit (Bob is NOT in this one)
        val kitB = medKitService.createNew(alice.id)
        entityManager.flush()
        entityManager.clear()
        // ACT: Move drug to private kit
        medKitDrugOrchestrator.moveDrug(drug.id, kitB.id, alice.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Bob's plan is purged, Alice's remains
        val alicePlan = dbHelper.userPlan(alice.id, drug.id)
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)
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
            medKitDrugOrchestrator.createDrugInMedKit(kitA.id, DrugCreateRequest("Migrating Meds", qty(10.0), "pcs"), alice.id)

        // ACT: Delete Kit A and migrate drugs to Kit B
        entityManager.flush()
        entityManager.clear()
        medKitDrugOrchestrator.delete(kitA.id, alice.id, kitB.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Kit A is gone, but the drug survives in Kit B
        val survivingDrug = drugService.findById(drug.id)

        assertNotNull(survivingDrug, "Drug should not have been deleted")
        assertEquals(kitB.id, survivingDrug.medKitId, "Drug should be re-parented to Kit B")
    }
}
