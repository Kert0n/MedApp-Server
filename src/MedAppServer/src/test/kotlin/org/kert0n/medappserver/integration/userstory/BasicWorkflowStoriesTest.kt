package org.kert0n.medappserver.integration.userstory

import jakarta.persistence.EntityManager
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class BasicWorkflowStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper


    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var drugs: DrugApplicationService

    @Autowired
    private lateinit var medKits: MedKitApplicationService

    /**
     * Story 1: Anna creates her first medkit and adds some drugs
     *
     * Validates: User registration, medkit creation, drug management, consumption tracking
     */
    @Test
    fun `Story 1 - New user Anna creates and manages her medkit`() {
        // Anna signs up
        val anna = User(
            id = UUID.randomUUID(),
            hashedKey = "anna_hashed_key_${UUID.randomUUID()}"
        )
        dbHelper.insert(anna)
        entityManager.flush()

        // Creates medkit
        val homeMedkit = medKitService.create(anna.id)
        assertNotNull(homeMedkit)

        // Adds drugs through the store directly
        val aspirin = Drug(
            id = UUID.randomUUID(),
            name = "Aspirin",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = "painkiller",
            manufacturer = null,
            country = null,
            description = null,
            medKitId = homeMedkit.id
        )
        dbHelper.insert(aspirin)

        val ibuprofen = Drug(
            id = UUID.randomUUID(),
            name = "Ibuprofen",
            quantity = Quantity(qty(50.0), dbHelper.unit()),
            category = "painkiller",
            manufacturer = null,
            country = null,
            description = null,
            medKitId = homeMedkit.id
        )
        dbHelper.insert(ibuprofen)
        entityManager.flush()

        // Anna takes 2 tablets of Aspirin
        drugService.consume(aspirin.id, qty(2.0), anna.id)
        entityManager.flush()
        entityManager.clear()

        // Check inventory
        val updatedAspirin = dbHelper.drug(aspirin.id)
        assertNotNull(updatedAspirin)
        assertQty(98.0, updatedAspirin.quantity, "Should have 98 tablets left")

        val drugs = drugService.ofMedKit(homeMedkit.id)
        assertEquals(2, drugs.size, "Should have 2 drugs in medkit")

        println("✅ Story 1 passed: Anna successfully created medkit and managed drugs")
    }

    /**
     * Story 2: Anna shares her medkit with Bob (her roommate)
     *
     * Validates: Multi-user medkit sharing, bidirectional relationships, data visibility
     */
    @Test
    fun `Story 2 - Anna shares medkit with roommate Bob`() {
        // Anna's medkit
        val anna = User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}")
        dbHelper.insert(anna)
        val medkit = medKitService.create(anna.id)

        val vitamins = Drug(
            id = UUID.randomUUID(),
            name = "Vitamin C",
            quantity = Quantity(qty(30.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(vitamins)
        entityManager.flush()

        // Bob signs up
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        dbHelper.insert(bob)
        entityManager.flush()

        // Anna shares with Bob via share key
        val shareKey = medKitService.invite(medkit.id, anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)
        entityManager.flush()
        entityManager.clear()

        // Both can see it
        val annaMedkits = medKitService.allOfUser(anna.id)
        val bobMedkits = medKitService.allOfUser(bob.id)

        assertEquals(1, annaMedkits.size)
        assertEquals(1, bobMedkits.size)
        assertEquals(annaMedkits[0], bobMedkits[0], "Should be the same medkit")

        // Verify the medkit has 2 users
        val sharedMedkit = medKitStore.findById(medkit.id)
        assertNotNull(sharedMedkit)
        assertEquals(2, sharedMedkit.members.size, "Medkit should have 2 users")

        println("✅ Story 2 passed: Anna successfully shared medkit with Bob")
    }

    /**
     * Story 3: Bob leaves shared medkit - his data is cleaned up
     *
     * Validates: User removal, cascade operations, data integrity
     */
    @Test
    fun `Story 3 - Bob leaves shared medkit, cleanup works correctly`() {
        // Setup shared medkit
        val anna = User(id = UUID.randomUUID(), hashedKey = "anna_${UUID.randomUUID()}")
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medkit.id, anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val drugData = Drug(
            id = UUID.randomUUID(),
            name = "Test Drug",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()
        entityManager.clear()

        // Verify both users have access
        val loadedMedkit = medKitStore.findById(medkit.id)!!
        assertEquals(2, loadedMedkit.members.size)

        // Bob leaves (drugs stay)
        medKits.leave(medkit.id, bob.id)
        entityManager.flush()
        entityManager.clear()

        // Medkit still exists with Anna only
        val updatedMedkit = medKitStore.findById(medkit.id)
        assertNotNull(updatedMedkit)
        assertEquals(1, updatedMedkit.members.size, "Only Anna should be in medkit")
        assertTrue(updatedMedkit.members.contains(anna.id))

        // Drug still exists
        val remainingDrug = dbHelper.drug(drugData.id)
        assertNotNull(remainingDrug, "Drug should still exist")

        println("✅ Story 3 passed: Bob left medkit, cleanup successful")
    }

    /**
     * Story 4: Migrating drugs when deleting a medkit
     *
     * Validates: Drug migration, medkit deletion, data preservation
     */
    @Test
    fun `Story 4 - User migrates drugs when deleting old medkit`() {
        // Create user and first medkit
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)
        val oldMedkit = medKitService.create(userData.id)

        // Add drugs
        val drugData1 = Drug(
            id = UUID.randomUUID(),
            name = "Drug A",
            quantity = Quantity(qty(50.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = oldMedkit.id
        )
        val drugData2 = Drug(
            id = UUID.randomUUID(),
            name = "Drug B",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = oldMedkit.id
        )
        dbHelper.insert(drugData1)
        dbHelper.insert(drugData2)

        // Create new medkit for migration
        val newMedkit = medKitService.create(userData.id)
        entityManager.flush()
        entityManager.clear()

        // Verify user has 2 medkits
        assertEquals(2, medKitService.allOfUser(userData.id).size)

        // Delete old medkit and move drugs
        medKits.delete(oldMedkit.id, userData.id, newMedkit.id)
        entityManager.flush()
        entityManager.clear()

        // Verify migration
        val drugsInNew = drugService.ofMedKit(newMedkit.id)
        assertEquals(2, drugsInNew.size, "All drugs should be in new medkit")
        val drugNames = drugsInNew.map { drug -> drug.name }
        assertTrue(drugNames.contains("Drug A"))
        assertTrue(drugNames.contains("Drug B"))

        // Old medkit should be gone
        val oldMedkitCheck = medKitStore.findById(oldMedkit.id)
        assertNull(oldMedkitCheck, "Old medkit should be deleted")

        // User should have only 1 medkit now
        assertEquals(1, medKitService.allOfUser(userData.id).size)

        println("✅ Story 4 passed: Drugs successfully migrated to new medkit")
    }

    /** Story 5: the pack emptied by an intake is destroyed, not left at zero. */
    @Test
    fun `Story 5 - User consumes all available drug quantity`() {
        val userData = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
        val drugData = Drug(
            id = UUID.randomUUID(),
            name = "Limited Drug",
            quantity = Quantity(qty(30.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)
        entityManager.flush()

        // Consume all in steps
        drugService.consume(drugData.id, qty(10.0), userData.id)
        drugService.consume(drugData.id, qty(10.0), userData.id)
        drugService.consume(drugData.id, qty(10.0), userData.id)
        entityManager.flush()
        entityManager.clear()

        val updatedDrug = dbHelper.drug(drugData.id)
        // Must be deleted
        assertNull(updatedDrug)

        println("✅ Story 5 passed: All drug quantity consumed correctly")
    }
}
