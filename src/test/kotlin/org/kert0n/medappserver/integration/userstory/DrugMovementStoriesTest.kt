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


    /** История 11: переезд сохраняет брони всех, кто пачку по-прежнему видит. */
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

        dbHelper.reserve(userData.id, painkiller.id, qty(20.0))

        drugs.moveToMedKit(painkiller.id, travelKit.id, dbHelper.drugVersion(painkiller.id), userData.id)

        val movedDrug = dbHelper.drug(painkiller.id)
        assertNotNull(movedDrug)
        assertEquals(travelKit.id, movedDrug.medKitId)

        val homeKitDrugs = drugService.ofMedKit(homeKit.id, userData.id)
        assertTrue(homeKitDrugs.isEmpty())

        val travelKitDrugs = drugService.ofMedKit(travelKit.id, userData.id)
        assertEquals(1, travelKitDrugs.size)

        val plan = dbHelper.userReservation(userData.id, painkiller.id)
        assertNotNull(plan, "Treatment plan should survive drug move")
        assertQty(20.0, plan)

        println("✅ Story 11 passed: Drug moved between medkits with treatment plan intact")
    }

    /** История 12: бронь поднимается свободно — с остатком пачки её никто не сверяет. */
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

        // Анна заявляет 40, Боб 30 — 70 из 100.
        dbHelper.reserve(anna.id, drugData.id, qty(40.0))
        dbHelper.reserve(bob.id, drugData.id, qty(30.0))

        val updated = reservationService.changeTo(
            reservationService.get(anna.id, drugData.id), qty(70.0), dbHelper.reservationsVersion(drugData.id, anna.id)
        )
        assertQty(70.0, updated.amount)
        assertQty(100.0, dbHelper.reservedOnDrug(drugData.id))

        // Выше содержимого пачки тоже можно: 200 + 30 на сотню таблеток — законное состояние.
        reservationService.changeTo(
            reservationService.get(anna.id, drugData.id), qty(200.0), dbHelper.reservationsVersion(drugData.id, anna.id)
        )
        assertQty(230.0, dbHelper.reservedOnDrug(drugData.id))

        println("✅ Story 12 passed: reservations are free to exceed the package")
    }

    /** История 13: уничтоженная пачка уносит брони с собой. */
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

        dbHelper.reserve(userData.id, drugData.id, qty(25.0))

        val plan = dbHelper.userReservation(userData.id, drugData.id)
        assertNotNull(plan)

        disposal.destroy(drugService.get(drugData.id, userData.id), dbHelper.drugVersion(drugData.id))

        val deletedDrug = dbHelper.drug(drugData.id)
        assertNull(deletedDrug)

        val deletedPlan = dbHelper.userReservation(userData.id, drugData.id)
        assertNull(deletedPlan)

        println("✅ Story 13 passed: Deleting drug removed its treatment plans")
    }

    /** История 14: перенос в более узкую аптечку снимает брони тех, кто в неё не входит. */
    @Test
    fun `Story 14 - Moving shared drug to private medkit removes other users treatment plans`() {
        val anna = dbHelper.insert(User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}"))
        val bob = dbHelper.insert(User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}"))
        val charlie = dbHelper.insert(User(id = Uuid.random(), hashedKey = "charlie_${Uuid.random()}"))

        val oldKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, anna.id), anna.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, anna.id), anna.id), charlie.id)

        // Новая аптечка — на Анну и Боба; Чарли в неё не входит.
        val newKit = medKitService.create(anna.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(newKit.id, anna.id), anna.id), bob.id)

        val drugData = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Special Meds", quantity = Quantity(qty(90.0), dbHelper.unit()), medKitId = oldKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )

        dbHelper.reserve(anna.id, drugData.id, qty(30.0))
        dbHelper.reserve(bob.id, drugData.id, qty(30.0))
        dbHelper.reserve(charlie.id, drugData.id, qty(30.0))


        medKits.delete(oldKit.id, dbHelper.medKitVersion(oldKit.id), anna.id, newKit.id)


        assertNotNull(dbHelper.userReservation(anna.id, drugData.id), "Anna should keep her plan")
        assertNotNull(dbHelper.userReservation(bob.id, drugData.id), "Bob should keep his plan")
        assertNull(
            dbHelper.userReservation(charlie.id, drugData.id),
            "Charlie's plan MUST be deleted for security"
        )

        println("✅ Story 14 passed: Migration security successfully audited treatment plans")
    }


    /** История 16: переезд одной пачки не трогает ни аптечку, ни остальные пачки. */
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


        drugs.moveToMedKit(drugDataToMove.id, targetKit.id, dbHelper.drugVersion(drugDataToMove.id), userData.id)


        val movedDrug = dbHelper.drug(drugDataToMove.id)
        assertNotNull(movedDrug, "Moved drug must not be deleted")
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug should point to new kit")

        val stayingDrug = dbHelper.drug(drugDataToStay.id)
        assertNotNull(stayingDrug, "Staying drug must not be affected")
        assertEquals(sourceKit.id, stayingDrug.medKitId)

        println("✅ Story 16 passed: Moving a single drug prevented orphan removal")
    }
}
