package org.kert0n.medappserver.integration.userstory

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class BasicWorkflowStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper



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

    /**
     * Story 1: Anna creates her first medkit and adds some drugs
     *
     * Validates: User registration, medkit creation, drug management, consumption tracking
     */
    @Test
    fun `Story 1 - New user Anna creates and manages her medkit`() {
        val anna = User(
            id = Uuid.random(),
            hashedKey = "anna_hashed_key_${Uuid.random()}"
        )
        dbHelper.insert(anna)

        val homeMedkit = medKitService.create(anna.id)
        assertNotNull(homeMedkit)

        val aspirin = Drug(
            id = Uuid.random(),
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
            id = Uuid.random(),
            name = "Ibuprofen",
            quantity = Quantity(qty(50.0), dbHelper.unit()),
            category = "painkiller",
            manufacturer = null,
            country = null,
            description = null,
            medKitId = homeMedkit.id
        )
        dbHelper.insert(ibuprofen)

        drugService.consume(drugService.get(aspirin.id, anna.id), qty(2.0), dbHelper.drugVersion(aspirin.id))

        val updatedAspirin = dbHelper.drug(aspirin.id)
        assertNotNull(updatedAspirin)
        assertQty(98.0, updatedAspirin.quantity, "Should have 98 tablets left")

        val drugs = drugService.ofMedKit(homeMedkit.id, anna.id)
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
        val anna = User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}")
        dbHelper.insert(anna)
        val medkit = medKitService.create(anna.id)

        val vitamins = Drug(
            id = Uuid.random(),
            name = "Vitamin C",
            quantity = Quantity(qty(30.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(vitamins)

        val bob = User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}")
        dbHelper.insert(bob)

        val shareKey = medKitService.invite(medKitService.get(medkit.id, anna.id), anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val annaMedkits = medKitService.allOfUser(anna.id)
        val bobMedkits = medKitService.allOfUser(bob.id)

        assertEquals(1, annaMedkits.size)
        assertEquals(1, bobMedkits.size)
        assertEquals(annaMedkits[0], bobMedkits[0], "Should be the same medkit")

        val sharedMedkit = dbHelper.medKit(medkit.id)
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
        val anna = User(id = Uuid.random(), hashedKey = "anna_${Uuid.random()}")
        val bob = User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}")
        dbHelper.insert(anna)
        dbHelper.insert(bob)

        val medkit = medKitService.create(anna.id)
        val shareKey = medKitService.invite(medKitService.get(medkit.id, anna.id), anna.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val drugData = Drug(
            id = Uuid.random(),
            name = "Test Drug",
            quantity = Quantity(qty(100.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)

        val loadedMedkit = dbHelper.medKit(medkit.id)!!
        assertEquals(2, loadedMedkit.members.size)

        // Bob leaves (drugs stay)
        medKits.leave(medkit.id, dbHelper.medKitVersion(medkit.id), bob.id)

        val updatedMedkit = dbHelper.medKit(medkit.id)
        assertNotNull(updatedMedkit)
        assertEquals(1, updatedMedkit.members.size, "Only Anna should be in medkit")
        assertTrue(updatedMedkit.members.contains(anna.id))

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
        val userData = User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}")
        dbHelper.insert(userData)
        val oldMedkit = medKitService.create(userData.id)

        val drugData1 = Drug(
            id = Uuid.random(),
            name = "Drug A",
            quantity = Quantity(qty(50.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = oldMedkit.id
        )
        val drugData2 = Drug(
            id = Uuid.random(),
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

        val newMedkit = medKitService.create(userData.id)

        assertEquals(2, medKitService.allOfUser(userData.id).size)

        medKits.delete(oldMedkit.id, dbHelper.medKitVersion(oldMedkit.id), userData.id, newMedkit.id)

        val drugsInNew = drugService.ofMedKit(newMedkit.id, userData.id)
        assertEquals(2, drugsInNew.size, "All drugs should be in new medkit")
        val drugNames = drugsInNew.map { drug -> drug.name }
        assertTrue(drugNames.contains("Drug A"))
        assertTrue(drugNames.contains("Drug B"))

        val oldMedkitCheck = dbHelper.medKit(oldMedkit.id)
        assertNull(oldMedkitCheck, "Old medkit should be deleted")

        assertEquals(1, medKitService.allOfUser(userData.id).size)

        println("✅ Story 4 passed: Drugs successfully migrated to new medkit")
    }

    /** Story 5: the pack emptied by an intake is destroyed, not left at zero. */
    @Test
    fun `Story 5 - User consumes all available drug quantity`() {
        val userData = User(id = Uuid.random(), hashedKey = "user_${Uuid.random()}")
        dbHelper.insert(userData)

        val medkit = medKitService.create(userData.id)
        val drugData = Drug(
            id = Uuid.random(),
            name = "Limited Drug",
            quantity = Quantity(qty(30.0), dbHelper.unit()),
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKitId = medkit.id
        )
        dbHelper.insert(drugData)

        disposal.consume(drugService.get(drugData.id, userData.id), qty(10.0), dbHelper.drugVersion(drugData.id))
        disposal.consume(drugService.get(drugData.id, userData.id), qty(10.0), dbHelper.drugVersion(drugData.id))
        disposal.consume(drugService.get(drugData.id, userData.id), qty(10.0), dbHelper.drugVersion(drugData.id))

        val updatedDrug = dbHelper.drug(drugData.id)
        assertNull(updatedDrug)

        println("✅ Story 5 passed: All drug quantity consumed correctly")
    }
}
