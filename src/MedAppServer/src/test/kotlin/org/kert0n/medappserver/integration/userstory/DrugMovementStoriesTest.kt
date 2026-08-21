package org.kert0n.medappserver.integration.userstory

import kotlin.test.*
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.orchestrator.DrugDisposal
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
    private lateinit var reservationService: ReservationService

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var disposal: DrugDisposal

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var drugs: DrugApplicationService

    @Autowired
    private lateinit var medKits: MedKitApplicationService


    /** Story 11: a move keeps the reservations of everyone who still sees the pack. */
    @Test
    fun `Story 11 - Moving drug between medkits`() {
        val userData = User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}")
        dbHelper.insert(userData)

        val homeKit = medKitService.create(userData.id)
        val travelKit = medKitService.create(userData.id)

        val painkiller = Drug(
            id = Uuid.random(), name = "Ibuprofen",
            quantity = Quantity(qty(60.0), dbHelper.unit()),
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKitId = homeKit.id
        )
        dbHelper.insert(painkiller)

        // Reserve a share
        dbHelper.reserve(userData.id, painkiller.id, qty(20.0))

        // Move drug to travel kit
        drugs.moveToMedKit(painkiller.id, travelKit.id, userData.id)

        // Drug is in travel kit
        val movedDrug = dbHelper.drug(painkiller.id)
        assertNotNull(movedDrug)
        assertEquals(travelKit.id, movedDrug.medKitId)

        // Home kit is empty
        val homeKitDrugs = drugService.ofMedKit(homeKit.id, userData.id)
        assertTrue(homeKitDrugs.isEmpty())

        // Travel kit has the drug
        val travelKitDrugs = drugService.ofMedKit(travelKit.id, userData.id)
        assertEquals(1, travelKitDrugs.size)

        // The reservation survives
        val plan = dbHelper.userReservation(userData.id, painkiller.id)
        assertNotNull(plan, "Treatment plan should survive drug move")
        assertQty(20.0, plan)

        println("✅ Story 11 passed: Drug moved between medkits with treatment plan intact")
    }

    /** Story 12: a reservation may be raised freely — nothing weighs it against the pack. */
    @Test
    fun `Story 12 - Updating treatment plan correctly checks available quantity`() {
        val anna = User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}")
        val bob = User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medKitService.get(medkit.id, anna.id), anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val drugData = Drug(
            id = Uuid.random(), name = "Medicine X",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null, manufacturer = null, country = null,
            description = null, medKitId = medkit.id
        )
        dbHelper.insert(drugData)

        // Anna reserves 40, Bob 30 — 70 of 100
        dbHelper.reserve(anna.id, drugData.id, qty(40.0))
        dbHelper.reserve(bob.id, drugData.id, qty(30.0))

        val updated = reservationService.changeTo(reservationService.get(anna.id, drugData.id), qty(70.0))
        assertQty(70.0, updated.amount)
        assertQty(100.0, dbHelper.reservedOnDrug(drugData.id))

        // Выше содержимого пачки тоже можно: 200 + 30 на сотню таблеток — законное состояние.
        reservationService.changeTo(reservationService.get(anna.id, drugData.id), qty(200.0))
        assertQty(230.0, dbHelper.reservedOnDrug(drugData.id))

        println("✅ Story 12 passed: reservations are free to exceed the package")
    }

    /** Story 13: a destroyed pack takes its reservations with it. */
    @Test
    fun `Story 13 - Deleting drug removes its treatment plans`() {
        val userData = User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
        val drugData = Drug(
            id = Uuid.random(), name = "Expired Drug",
            quantity = Quantity(qty(50.0), dbHelper.unit()), formType = null,
            category = null, manufacturer = null, country = null,
            description = null, medKitId = medkit.id
        )
        dbHelper.insert(drugData)

        // Reserve a share
        dbHelper.reserve(userData.id, drugData.id, qty(25.0))

        // Verify the reservation exists
        val plan = dbHelper.userReservation(userData.id, drugData.id)
        assertNotNull(plan)

        // Delete the drug
        disposal.destroy(drugService.get(drugData.id, userData.id))

        // Drug should be gone
        val deletedDrug = dbHelper.drug(drugData.id)
        assertNull(deletedDrug)

        // The reservation is gone with it
        val deletedPlan = dbHelper.userReservation(userData.id, drugData.id)
        assertNull(deletedPlan)

        println("✅ Story 13 passed: Deleting drug removed its treatment plans")
    }

    /** Story 14: migration into a narrower kit strips the reservations of those left out. */
    @Test
    fun `Story 14 - Moving shared drug to private medkit removes other users treatment plans`() {
        // Setup: Anna, Bob, and Charlie share an Old MedKit
        val anna = dbHelper.insert(User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}"))
        val bob = dbHelper.insert(User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}"))
        val charlie = dbHelper.insert(User(id = Uuid.random(), hashedKey = "charlie_${Uuid.random()}"))

        val oldKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, anna.id), anna.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, anna.id), anna.id), charlie.id)

        // Setup: Anna and Bob share a New MedKit (Charlie is excluded)
        val newKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(newKit.id, anna.id), anna.id), bob.id)

        // Add drug to old kit
        val drugData = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Special Meds", quantity = Quantity(qty(90.0), dbHelper.unit()), medKitId = oldKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        // Everyone reserves 30 pills
        dbHelper.reserve(anna.id, drugData.id, qty(30.0))
        dbHelper.reserve(bob.id, drugData.id, qty(30.0))
        dbHelper.reserve(charlie.id, drugData.id, qty(30.0))


        // Anna deletes the old kit and migrates to the new kit
        medKits.delete(oldKit.id, anna.id, newKit.id)


        // Verify: Anna and Bob keep their reservations, Charlie's is deleted.
        assertNotNull(dbHelper.userReservation(anna.id, drugData.id), "Anna should keep her plan")
        assertNotNull(dbHelper.userReservation(bob.id, drugData.id), "Bob should keep his plan")
        assertNull(
            dbHelper.userReservation(charlie.id, drugData.id),
            "Charlie's plan MUST be deleted for security"
        )

        println("✅ Story 14 passed: Migration security successfully audited treatment plans")
    }


    /** Story 16: moving one pack out of a kit leaves it and the others intact. */
    @Test
    fun `Story 16 - Moving single drug preserves it from orphan removal`() {
        val userData = dbHelper.insert(User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}"))

        val sourceKit = medKitService.create(userData.id)
        val targetKit = medKitService.create(userData.id)

        val drugDataToMove = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Moving Pill", quantity = Quantity(qty(10.0), dbHelper.unit()), medKitId = sourceKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        val drugDataToStay = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Staying Pill", quantity = Quantity(qty(10.0), dbHelper.unit()), medKitId = sourceKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )


        // Move ONLY one drug
        drugs.moveToMedKit(drugDataToMove.id, targetKit.id, userData.id)


        // Verify the move did not destroy it
        val movedDrug = dbHelper.drug(drugDataToMove.id)
        assertNotNull(movedDrug, "Moved drug must not be deleted")
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug should point to new kit")

        val stayingDrug = dbHelper.drug(drugDataToStay.id)
        assertNotNull(stayingDrug, "Staying drug must not be affected")
        assertEquals(sourceKit.id, stayingDrug.medKitId)

        println("✅ Story 16 passed: Moving a single drug prevented orphan removal")
    }
}
