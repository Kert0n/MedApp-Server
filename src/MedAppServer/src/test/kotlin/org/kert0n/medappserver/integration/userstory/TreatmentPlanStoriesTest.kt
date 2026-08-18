package org.kert0n.medappserver.integration.userstory

import org.kert0n.medappserver.services.aggregate.ReservationService
import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
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
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var reservationService: ReservationService

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKits: MedKitApplicationService


    /** Story 6: reserve a share, then take from the pack. */
    @Test
    fun `Story 6 - User creates treatment plan and records intakes`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
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

        // Reserve 30 tablets
        val plan = reservationService.create(userId = userData.id, drugData.id, qty(30.0))
        assertNotNull(plan)
        entityManager.flush()

        // Verify the reservation was created
        val createdPlan = dbHelper.userReservation(userData.id, drugData.id)
        assertNotNull(createdPlan, "Plan should be created")
        assertQty(30.0, createdPlan, "Planned amount should be 30")

        // Record some intakes
        drugService.consume(drugData.id, qty(5.0), userData.id, dbHelper.drugVersion(drugData.id))
        drugService.consume(drugData.id, qty(5.0), userData.id, dbHelper.drugVersion(drugData.id))
        entityManager.flush()
        entityManager.clear()

        // Verify drug quantity decreased
        val updatedDrug = dbHelper.drug(drugData.id)
        assertNotNull(updatedDrug)
        assertQty(90.0, updatedDrug.quantity, "Drug quantity should be 90 after 10 consumed")

        println("✅ Story 6 passed: Treatment plan and intakes work correctly")
    }

    /** Story 7: several people reserve shares of the same pack, each their own. */
    @Test
    fun `Story 7 - Multiple users create treatment plans on shared drug`() {
        // Setup: Anna and Bob share a medkit with 100 tablets of Vitamin C
        val anna = User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}")
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medkit.id, anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

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

        // Anna reserves 40 tablets
        reservationService.create(anna.id, vitaminC.id, qty(40.0))
        entityManager.flush()

        // Bob reserves 50 more
        reservationService.create(bob.id, vitaminC.id, qty(50.0))
        entityManager.flush()
        entityManager.clear()
        // 90 reserved on the pack in total
        assertQty(90.0, dbHelper.reservedOnDrug(vitaminC.id), "Total planned should be 90")

        // Each has their own reservation
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
        // Mom creates a family medkit
        val mom = User(id = UUID.randomUUID(), hashedKey = "mom_${UUID.randomUUID()}")
        val dad = User(id = UUID.randomUUID(), hashedKey = "dad_${UUID.randomUUID()}")
        val child = User(id = UUID.randomUUID(), hashedKey = "child_${UUID.randomUUID()}")
        dbHelper.insert(mom)
        dbHelper.insert(dad)
        dbHelper.insert(child)
        entityManager.flush()

        val familyKit = medKitService.create(mom.id)
        val dadKey = medKitService.invite(familyKit.id, mom.id)
        medKitService.joinByInvitation(dadKey, dad.id)
        val childKey = medKitService.invite(familyKit.id, mom.id)
        medKitService.joinByInvitation(childKey, child.id)
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

        // Everyone reserves 30 vitamins
        reservationService.create(mom.id, vitamins.id, qty(30.0))
        reservationService.create(dad.id, vitamins.id, qty(30.0))
        reservationService.create(child.id, vitamins.id, qty(30.0))
        entityManager.flush()
        entityManager.clear()
        // 90 reserved — the whole pack
        assertQty(90.0, dbHelper.reservedOnDrug(vitamins.id))

        // Everyone takes their daily vitamin
        drugService.consume(vitamins.id, qty(1.0), mom.id, dbHelper.drugVersion(vitamins.id))
        entityManager.flush()
        drugService.consume(vitamins.id, qty(1.0), dad.id, dbHelper.drugVersion(vitamins.id))
        entityManager.flush()
        drugService.consume(vitamins.id, qty(1.0), child.id, dbHelper.drugVersion(vitamins.id))
        entityManager.flush()
        entityManager.clear()

        // Check vitamins after 1 day
        val updatedVitamins = dbHelper.drug(vitamins.id)
        assertNotNull(updatedVitamins)
        assertQty(87.0, updatedVitamins.quantity, "Should be 90 - 3 = 87")

        // 3 users in the medkit
        val medkit = medKitStore.findById(familyKit.id)
        assertNotNull(medkit)
        assertEquals(3, medkit.members.size)

        // Child leaves the medkit
        medKits.leave(familyKit.id, child.id, dbHelper.medKitVersion(familyKit.id))
        entityManager.flush()
        entityManager.clear()

        // Medkit still has mom and dad
        val updatedKit = medKitStore.findById(familyKit.id)
        assertNotNull(updatedKit)
        assertEquals(2, updatedKit.members.size)

        println("✅ Story 10 passed: Complete family medkit lifecycle")
    }
}
