package org.kert0n.medappserver.integration.userstory

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class TreatmentPlanStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper



    @Autowired
    private lateinit var reservationService: ReservationService

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var drugs: DrugApplicationService

    @Autowired
    private lateinit var medKits: MedKitApplicationService


    /** Story 6: reserve a share, then take from the pack. */
    @Test
    fun `Story 6 - User creates treatment plan and records intakes`() {
        val userData = User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
        val drugData = Drug(
            id = Uuid.random(),
            name = "Treatment Drug",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)

        val plan = dbHelper.reserve(userData.id, drugData.id, qty(30.0))
        assertNotNull(plan)

        val createdPlan = dbHelper.userReservation(userData.id, drugData.id)
        assertNotNull(createdPlan, "Plan should be created")
        assertQty(30.0, createdPlan, "Planned amount should be 30")

        drugService.consume(drugService.get(drugData.id, userData.id), qty(5.0), dbHelper.drugVersion(drugData.id))
        drugService.consume(drugService.get(drugData.id, userData.id), qty(5.0), dbHelper.drugVersion(drugData.id))

        val updatedDrug = dbHelper.drug(drugData.id)
        assertNotNull(updatedDrug)
        assertQty(90.0, updatedDrug.quantity, "Drug quantity should be 90 after 10 consumed")

        println("✅ Story 6 passed: Treatment plan and intakes work correctly")
    }

    /** Story 7: several people reserve shares of the same pack, each their own. */
    @Test
    fun `Story 7 - Multiple users create treatment plans on shared drug`() {
        // Setup: Anna and Bob share a medkit with 100 tablets of Vitamin C
        val anna = User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}")
        val bob = User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medKitService.get(medkit.id, anna.id), anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val vitaminC = Drug(
            id = Uuid.random(),
            name = "Vitamin C",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(vitaminC)

        dbHelper.reserve(anna.id, vitaminC.id, qty(40.0))

        dbHelper.reserve(bob.id, vitaminC.id, qty(50.0))
        // 90 reserved on the pack in total
        assertQty(90.0, dbHelper.reservedOnDrug(vitaminC.id), "Total planned should be 90")

        val annaPlan = dbHelper.userReservation(anna.id, vitaminC.id)
        val bobPlan = dbHelper.userReservation(bob.id, vitaminC.id)
        assertNotNull(annaPlan)
        assertNotNull(bobPlan)
        assertQty(40.0, annaPlan)
        assertQty(50.0, bobPlan)

        println("✅ Story 7 passed: Multiple users created treatment plans on shared drug")
    }



    /**
     * Story 10: Complete family medkit lifecycle
     *
     * Validates: Full end-to-end workflow from creation to cleanup
     */
    @Test
    fun `Story 10 - Complete family medkit lifecycle`() {
        val mom = User(id = Uuid.random(), hashedKey = "mom_${Uuid.random()}")
        val dad = User(id = Uuid.random(), hashedKey = "dad_${Uuid.random()}")
        val child = User(id = Uuid.random(), hashedKey = "child_${Uuid.random()}")
        dbHelper.insert(mom)
        dbHelper.insert(dad)
        dbHelper.insert(child)

        val familyKit = medKitService.create(mom.id)
        val dadKey = medKitService.invite(medKitService.get(familyKit.id, mom.id), mom.id)
        medKitService.joinByInvitation(dadKey, dad.id)
        val childKey = medKitService.invite(medKitService.get(familyKit.id, mom.id), mom.id)
        medKitService.joinByInvitation(childKey, child.id)

        val aspirin = Drug(
            id = Uuid.random(), name = "Children's Aspirin",
            quantity = Quantity(qty(200.0), dbHelper.unit()),
            category = "painkiller", manufacturer = null, country = null,
            description = null, medKitId = familyKit.id
        )
        val vitamins = Drug(
            id = Uuid.random(), name = "Multivitamins",
            quantity = Quantity(qty(90.0), dbHelper.unit()),
            category = "supplement", manufacturer = null, country = null,
            description = null, medKitId = familyKit.id
        )
        dbHelper.insert(aspirin)
        dbHelper.insert(vitamins)

        dbHelper.reserve(mom.id, vitamins.id, qty(30.0))
        dbHelper.reserve(dad.id, vitamins.id, qty(30.0))
        dbHelper.reserve(child.id, vitamins.id, qty(30.0))
        // 90 reserved — the whole pack
        assertQty(90.0, dbHelper.reservedOnDrug(vitamins.id))

        drugService.consume(drugService.get(vitamins.id, mom.id), qty(1.0), dbHelper.drugVersion(vitamins.id))
        drugService.consume(drugService.get(vitamins.id, dad.id), qty(1.0), dbHelper.drugVersion(vitamins.id))
        drugService.consume(drugService.get(vitamins.id, child.id), qty(1.0), dbHelper.drugVersion(vitamins.id))

        val updatedVitamins = dbHelper.drug(vitamins.id)
        assertNotNull(updatedVitamins)
        assertQty(87.0, updatedVitamins.quantity, "Should be 90 - 3 = 87")

        val medkit = dbHelper.medKit(familyKit.id)
        assertNotNull(medkit)
        assertEquals(3, medkit.members.size)

        medKits.leave(familyKit.id, dbHelper.medKitVersion(familyKit.id), child.id)

        val updatedKit = dbHelper.medKit(familyKit.id)
        assertNotNull(updatedKit)
        assertEquals(2, updatedKit.members.size)

        println("✅ Story 10 passed: Complete family medkit lifecycle")
    }
}
