package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.services.models.ReservationService
import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class ComplexWorkflowStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Autowired
    private lateinit var reservationService: ReservationService


    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator


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
        val alice = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "alice_${UUID.randomUUID()}"))
        val bob = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}"))
        val charlie = dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = "charlie_${UUID.randomUUID()}"))

        val homeKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(homeKit.id, alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(homeKit.id, alice.id), charlie.id)

        val allergyMeds = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Allergy Meds", quantity = Quantity(qty(60.0), dbHelper.unit()), medKitId = homeKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )
        val painkillers = dbHelper.insert(
            Drug(
                id = UUID.randomUUID(), name = "Painkillers", quantity = Quantity(qty(100.0), dbHelper.unit()), medKitId = homeKit.id, formType = null,
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
        reservationService.create(alice.id, allergyMeds.id, qty(20.0))
        reservationService.create(bob.id, allergyMeds.id, qty(20.0))
        reservationService.create(charlie.id, allergyMeds.id, qty(20.0))

        // Painkillers: 100 total. Bob plans 30, Charlie plans 30.
        reservationService.create(bob.id, painkillers.id, qty(30.0))
        reservationService.create(charlie.id, painkillers.id, qty(30.0))

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

        val updatedAllergyMeds = dbHelper.requireDrug(allergyMeds.id)
        assertQty(30.0, updatedAllergyMeds.quantity, "Stock should be 30")

        // Бронь не двигается: пачка уменьшилась, а сколько из своей брони оставить — дело
        // Алисы. Раньше сервер ужимал её сам, и это было решением за клиента.
        val aliceAllergyReservation = dbHelper.userReservation(alice.id, allergyMeds.id)!!
        assertQty(20.0, aliceAllergyReservation, "бронь Алисы осталась прежней")

        // ==========================================
        // PHASE 4: Single Drug Move (Security Audit)
        // ==========================================
        // Alice makes a private travel kit and takes the Painkillers.
        val travelKit = medKitService.create(alice.id)

        entityManager.flush()
        entityManager.clear()

        medKitDrugOrchestrator.moveDrug(painkillers.id, travelKit.id, alice.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Bob and Charlie lost their Painkiller plans because they can't see the Travel Kit
        assertNull(dbHelper.userReservation(bob.id, painkillers.id), "Bob's plan must be deleted")
        assertNull(dbHelper.userReservation(charlie.id, painkillers.id), "Charlie's plan must be deleted")

        val movedPainkillers = dbHelper.requireDrug(painkillers.id)
        assertEquals(travelKit.id, movedPainkillers.medKitId, "Drug successfully moved")

        // ==========================================
        // PHASE 5: Kill & Migrate (The Final Boss)
        // ==========================================
        // Alice deletes Home Kit. She moves remaining Allergy Meds to a new "Duo Kit" with just Bob.
        val duoKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(duoKit.id, alice.id), bob.id)

        entityManager.flush()
        entityManager.clear()

        // Perform the complex deletion migration
        medKitDrugOrchestrator.delete(homeKit.id, alice.id, duoKit.id)

        entityManager.flush()
        entityManager.clear()

        // Verify Home Kit is dead
        assertNull(medKitStore.findById(homeKit.id), "Home kit must be completely deleted")

        // Verify Allergy Meds moved safely without orphan removal
        val migratedAllergyMeds = dbHelper.drug(allergyMeds.id)
        assertNotNull(migratedAllergyMeds, "Allergy meds must survive the migration")
        assertEquals(duoKit.id, migratedAllergyMeds.medKitId, "Allergy meds are in Duo Kit")

        // Verify Charlie's Allergy Meds plan was stripped because he isn't in Duo Kit
        assertNull(
            dbHelper.userReservation(charlie.id, allergyMeds.id),
            "Charlie's last plan must be deleted"
        )

        // Брони Алисы и Боба переехали как были: их никто не ужимал
        val finalAlicePlan = dbHelper.userReservation(alice.id, allergyMeds.id)!!
        assertQty(20.0, finalAlicePlan, "бронь Алисы переехала неизменной")

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
        medKitService.leave(duoKitCheck1.id, alice.id)

        entityManager.flush()
        entityManager.clear()

        assertNull(medKitStore.findById(duoKit.id), "Duo kit must auto-delete when last user leaves")
        assertNull(
            dbHelper.drug(allergyMeds.id),
            "Cascade should kill the drugs inside the abandoned kit"
        )

        println("✅ Story 17 passed: The Roommate Saga completed without a single JPA integrity violation")
    }

    @Test
    fun `Story 18 - Full Lifecycle Alterations, Movement, and Deletion`() {
        // ── Setup ──
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")

        val sourceKit = medKitService.create(alice.id)
        val targetKit = medKitService.create(alice.id) // Only Alice has access to this one
        medKitService.joinByInvitation(medKitService.invite(sourceKit.id, alice.id), bob.id)

        // Alice adds 100 tablets to sourceKit
        val createDrugDto = DrugCreateRequest(
            name = "LifePill", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id
            )
        val drug = medKitDrugOrchestrator.createDrugInMedKit(sourceKit.id, createDrugDto, alice.id)
        dbHelper.flushAndClear()

        // Alice and Bob create treatment plans (40 each, total 80)
        reservationService.create(alice.id, drug.id, qty(40.0))
        reservationService.create(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // ── Phase 1: Alter treatment plan ──
        // Bob increases his plan from 40 to 60.
        // Allowed because 100 stock - 40 Alice = 60 available.
        reservationService.changeTo(bob.id, drug.id, qty(60.0))
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.userReservation(bob.id, drug.id)!!, "Bob's plan updated to 60")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "Alice's plan unchanged at 40")

        // ── Phase 2: Alter Drug (The Spill) ──
        // Алиса разлила половину: 50 из 100 больше нет. Брони при этом не двигаются — вместе
        // они теперь превышают содержимое пачки, и это законное состояние, за которое отвечают
        // их владельцы.
        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id)!!, "в пачке осталось 50")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "бронь Алисы не тронута")
        assertQty(60.0, dbHelper.userReservation(bob.id, drug.id)!!, "бронь Боба не тронута")
        assertQty(100.0, dbHelper.reservedOnDrug(drug.id), "заявлено больше, чем в пачке")

        // ── Phase 3: Move Drug ──
        // Alice moves the drug to targetKit (where Bob has no access).
        medKitDrugOrchestrator.moveDrug(drug.id, targetKit.id, alice.id)
        dbHelper.flushAndClear()

        val movedDrug = dbHelper.requireDrug(drug.id)
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug successfully moved to targetKit")

        // The ultimate security check: Bob's plan must be gone
        assertNull(dbHelper.userReservation(bob.id, drug.id), "Bob's plan MUST be stripped due to lost access")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "бронь Алисы не тронута")

        // ── Phase 4: Privacy-by-Default Deletion ──
        // Alice deletes the drug completely.
        drugService.delete(drug.id, alice.id)
        dbHelper.flushAndClear()

        // Verify absolute destruction
        assertNull(dbHelper.drugQuantity(drug.id), "Drug record completely purged")
        assertNull(dbHelper.userReservation(alice.id, drug.id), "Alice's plan completely purged along with the drug")

        println("✅ Story 18 passed: Updates, dynamic scaling, access stripping on move, and total deletion worked perfectly.")
    }

    private fun createTestUser(name: String): User {
        // Using repository directly to bypass any complex auth logic in UserService if necessary
        return dbHelper.insert(User(id = UUID.randomUUID(), hashedKey = name))
    }

    @Test
    fun `Story 19 - Roommate can move drug even without personal treatment plan`() {
        // SETUP: Alice owns a kit, Bob is a roommate
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")

        val kitA = medKitService.create(alice.id)
        val shareKey = medKitService.invite(kitA.id, alice.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        // Alice creates a drug
        val drug = drugService.create(DrugCreateRequest("Shared Meds", qty(10.0), dbHelper.unit().id), kitA.id, alice.id)

        // Bob creates a private kit
        val kitB = medKitService.create(bob.id)

        // ACT: Bob moves the drug to his private kit
        // This fails if the query uses an INNER JOIN on the 'usings' table
        assertDoesNotThrow {
            medKitDrugOrchestrator.moveDrug(drug.id, kitB.id, bob.id)
        }

        // VERIFY: Drug moved
        val updatedDrug = dbHelper.requireDrug(drug.id)
        assertEquals(kitB.id, updatedDrug.medKitId)
    }

    @Test
    fun `Verify movement strips unauthorized treatment plans`() {
        // SETUP: Shared kit with Alice and Bob
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")
        val kitA = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kitA.id, alice.id), bob.id)

        val drug = drugService.create(DrugCreateRequest("Audit Meds", qty(10.0), dbHelper.unit().id), kitA.id, alice.id)

        // Both have plans
        reservationService.create(alice.id, drug.id, qty(5.0))
        reservationService.create(bob.id, drug.id, qty(2.0))

        // Alice has a private kit (Bob is NOT in this one)
        val kitB = medKitService.create(alice.id)
        entityManager.flush()
        entityManager.clear()
        // ACT: Move drug to private kit
        medKitDrugOrchestrator.moveDrug(drug.id, kitB.id, alice.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Bob's plan is purged, Alice's remains
        val alicePlan = dbHelper.userReservation(alice.id, drug.id)
        val bobPlan = dbHelper.userReservation(bob.id, drug.id)
        assertNotNull(alicePlan, "Alice should keep her plan")
        assertNull(bobPlan, "Bob's plan must be deleted because he lost access to the drug")
    }

    @Test
    fun `Verify drug migration during MedKit deletion`() {
        // SETUP: Alice has Kit A and Kit B
        val alice = createTestUser("alice")
        val kitA = medKitService.create(alice.id)
        val kitB = medKitService.create(alice.id)
        entityManager.flush()
        entityManager.clear()
        val drug =
            medKitDrugOrchestrator.createDrugInMedKit(kitA.id, DrugCreateRequest("Migrating Meds", qty(10.0), dbHelper.unit().id), alice.id)

        // ACT: Delete Kit A and migrate drugs to Kit B
        entityManager.flush()
        entityManager.clear()
        medKitDrugOrchestrator.delete(kitA.id, alice.id, kitB.id)
        entityManager.flush()
        entityManager.clear()
        // VERIFY: Kit A is gone, but the drug survives in Kit B
        val survivingDrug = dbHelper.drug(drug.id)

        assertNotNull(survivingDrug, "Drug should not have been deleted")
        assertEquals(kitB.id, survivingDrug.medKitId, "Drug should be re-parented to Kit B")
    }
}
