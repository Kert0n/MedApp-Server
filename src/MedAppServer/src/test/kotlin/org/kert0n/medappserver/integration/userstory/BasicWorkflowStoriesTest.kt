package org.kert0n.medappserver.integration.userstory

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BasicWorkflowStoriesTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var medKitRepository: MedKitRepository

    @Autowired
    private lateinit var drugRepository: DrugRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var medKitDrugServices: MedKitDrugServices

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
        userRepository.save(anna)
        entityManager.flush()

        // Creates medkit
        val homeMedkit = medKitService.createNew(anna.id)
        assertNotNull(homeMedkit)

        // Adds drugs using repository directly (simulating controller layer)
        val aspirin = Drug(
            id = UUID.randomUUID(),
            name = "Aspirin",
            quantity = 100.0.toBigDecimal(),
            quantityUnit = "tablets",
            formType = "tablet",
            category = "painkiller",
            manufacturer = null,
            country = null,
            description = null,
            medKit = homeMedkit
        )
        drugRepository.save(aspirin)

        val ibuprofen = Drug(
            id = UUID.randomUUID(),
            name = "Ibuprofen",
            quantity = 50.0.toBigDecimal(),
            quantityUnit = "tablets",
            formType = "tablet",
            category = "painkiller",
            manufacturer = null,
            country = null,
            description = null,
            medKit = homeMedkit
        )
        drugRepository.save(ibuprofen)
        entityManager.flush()

        // Anna takes 2 tablets of Aspirin
        drugService.consumeDrug(aspirin.id, 2.0.toBigDecimal(), anna.id)
        entityManager.flush()
        entityManager.clear()

        // Check inventory
        val updatedAspirin = drugRepository.findById(aspirin.id).orElse(null)
        assertNotNull(updatedAspirin)
        org.kert0n.medappserver.testutil.assertDecimalEquals(98.0.toBigDecimal(), updatedAspirin.quantity, "Should have 98 tablets left")

        val drugs = drugRepository.findAllByMedKitId(homeMedkit.id)
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
        userRepository.save(anna)
        val medkit = medKitService.createNew(anna.id)

        val vitamins = Drug(
            id = UUID.randomUUID(),
            name = "Vitamin C",
            quantity = 30.0.toBigDecimal(),
            quantityUnit = "tablets",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(vitamins)
        entityManager.flush()

        // Bob signs up
        val bob = User(id = UUID.randomUUID(), hashedKey = "bob_${UUID.randomUUID()}")
        userRepository.save(bob)
        entityManager.flush()

        // Anna shares with Bob via share key
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, anna.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)
        entityManager.flush()
        entityManager.clear()

        // Both can see it
        val annaMedkits = medKitService.findAllByUser(anna.id)
        val bobMedkits = medKitService.findAllByUser(bob.id)

        assertEquals(1, annaMedkits.size)
        assertEquals(1, bobMedkits.size)
        assertEquals(annaMedkits[0].id, bobMedkits[0].id, "Should be the same medkit")

        // Verify the medkit has 2 users
        val sharedMedkit = medKitRepository.findById(medkit.id).orElse(null)
        assertNotNull(sharedMedkit)
        assertEquals(2, sharedMedkit.users.size, "Medkit should have 2 users")

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
        userRepository.save(anna)
        userRepository.save(bob)

        val medkit = medKitService.createNew(anna.id)
        val shareKey = medKitService.generateMedKitShareKey(medkit.id, anna.id)
        medKitService.joinMedKitByKey(shareKey, bob.id)

        val drug = Drug(
            id = UUID.randomUUID(),
            name = "Test Drug",
            quantity = 100.0.toBigDecimal(),
            quantityUnit = "ml",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = medkit
        )
        drugRepository.save(drug)
        entityManager.flush()
        entityManager.clear()

        // Verify both users have access
        val loadedMedkit = medKitRepository.findById(medkit.id).get()
        assertEquals(2, loadedMedkit.users.size)

        // Bob leaves (drugs stay)
        medKitDrugServices.removeUserFromMedKit(medkit.id, bob.id)
        entityManager.flush()
        entityManager.clear()

        // Medkit still exists with Anna only
        val updatedMedkit = medKitRepository.findById(medkit.id).orElse(null)
        assertNotNull(updatedMedkit)
        assertEquals(1, updatedMedkit.users.size, "Only Anna should be in medkit")
        assertTrue(updatedMedkit.users.any { it.id == anna.id })

        // Drug still exists
        val remainingDrug = drugRepository.findById(drug.id).orElse(null)
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
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)
        val oldMedkit = medKitService.createNew(user.id)

        // Add drugs
        val drug1 = Drug(
            id = UUID.randomUUID(),
            name = "Drug A",
            quantity = 50.0.toBigDecimal(),
            quantityUnit = "tablets",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = oldMedkit
        )
        val drug2 = Drug(
            id = UUID.randomUUID(),
            name = "Drug B",
            quantity = 100.0.toBigDecimal(),
            quantityUnit = "ml",
            formType = null,
            category = null,
            manufacturer = null,
            country = null,
            description = null,
            medKit = oldMedkit
        )
        drugRepository.save(drug1)
        drugRepository.save(drug2)

        // Create new medkit for migration
        val newMedkit = medKitService.createNew(user.id)
        entityManager.flush()
        entityManager.clear()

        // Verify user has 2 medkits
        assertEquals(2, medKitService.findAllByUser(user.id).size)

        // Delete old medkit and move drugs
        medKitDrugServices.delete(oldMedkit.id, user.id, newMedkit.id)
        entityManager.flush()
        entityManager.clear()

        // Verify migration
        val drugsInNew = drugRepository.findAllByMedKitId(newMedkit.id)
        assertEquals(2, drugsInNew.size, "All drugs should be in new medkit")
        val drugNames = drugsInNew.map { drug -> drug.name }
        assertTrue(drugNames.contains("Drug A"))
        assertTrue(drugNames.contains("Drug B"))

        // Old medkit should be gone
        val oldMedkitCheck = medKitRepository.findById(oldMedkit.id).orElse(null)
        assertNull(oldMedkitCheck, "Old medkit should be deleted")

        // User should have only 1 medkit now
        assertEquals(1, medKitService.findAllByUser(user.id).size)

        println("✅ Story 4 passed: Drugs successfully migrated to new medkit")
    }

    /**
     * Story 5: Edge case - consuming all drug quantity
     * 
     * Validates: Boundary conditions, zero quantity handling
     */
    @Test
    fun `Story 5 - User consumes all available drug quantity`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "user_${UUID.randomUUID()}")
        userRepository.save(user)

        val medkit = medKitService.createNew(user.id)
        val drug = Drug(
            id = UUID.randomUUID(),
            name = "Limited Drug",
            quantity = 30.0.toBigDecimal(),
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

        // Consume all in steps
        drugService.consumeDrug(drug.id, 10.0.toBigDecimal(), user.id)
        drugService.consumeDrug(drug.id, 10.0.toBigDecimal(), user.id)
        drugService.consumeDrug(drug.id, 10.0.toBigDecimal(), user.id)
        entityManager.flush()
        entityManager.clear()

        // Drug quantity should be exactly zero
        val updatedDrug = drugRepository.findById(drug.id).orElse(null)
        // Must be deleted
        assertNull(updatedDrug)

        println("✅ Story 5 passed: All drug quantity consumed correctly")
    }
}
