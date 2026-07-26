package org.kert0n.medappserver.integration

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.kert0n.medappserver.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.*
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@PostgresIntegrationTest
@Transactional
class RepositoryIntegrationTests {

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

    private fun createUser(key: String = "key_${UUID.randomUUID()}"): User {
        return userRepository.save(User(id = UUID.randomUUID(), hashedKey = key))
    }

    private fun createMedKitForUser(user: User): MedKit {
        val medKit = medKitRepository.save(MedKit())
        user.medKits.add(medKit)
        medKit.users.add(user)
        entityManager.flush()
        return medKit
    }

    private fun createDrug(medKit: MedKit, name: String = "Drug", quantity: Double = 100.0): Drug {
        return drugRepository.save(
            Drug(
                name = name,
                quantity = qty(quantity),
                quantityUnit = "tablets",
                formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null,
                medKit = medKit
            )
        )
    }

    // === DrugRepository Tests ===

    @Test
    fun `DrugRepository - findAllByMedKitId returns drugs in medkit`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug1 = createDrug(medKit, "Drug A")
        val drug2 = createDrug(medKit, "Drug B")
        entityManager.flush()
        entityManager.clear()

        val drugs = drugRepository.findAllByMedKitId(medKit.id)
        assertEquals(2, drugs.size)
        assertTrue(drugs.any { it.name == "Drug A" })
        assertTrue(drugs.any { it.name == "Drug B" })
    }

    @Test
    fun `DrugRepository - findAllByMedKitId returns empty list for empty medkit`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        entityManager.flush()

        val drugs = drugRepository.findAllByMedKitId(medKit.id)
        assertTrue(drugs.isEmpty())
    }

    @Test
    fun `DrugRepository - findByIdAndMedKitUsersId returns drug for authorized user`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug = createDrug(medKit)
        entityManager.flush()
        entityManager.clear()

        val found = drugRepository.findByIdAndMedKitUsersId(drug.id, user.id)
        assertNotNull(found)
        assertEquals(drug.id, found.id)
    }

    @Test
    fun `DrugRepository - findByIdAndMedKitUsersId returns null for unauthorized user`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        val drug = createDrug(medKit)
        entityManager.flush()
        entityManager.clear()

        val found = drugRepository.findByIdAndMedKitUsersId(drug.id, user2.id)
        assertNull(found)
    }

    @Test
    fun `DrugRepository - findByUsingsUserId returns drugs user has treatment plans for`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug1 = createDrug(medKit, "Drug A")
        val drug2 = createDrug(medKit, "Drug B")
        entityManager.flush()

        // Create using for drug1 only
        val using = Using(
            usingKey = UsingKey(user.id, drug1.id),
            user = user,
            drug = drug1,
            plannedAmount = qty(10.0)
        )
        usingRepository.save(using)
        entityManager.flush()
        entityManager.clear()

        val drugs = drugRepository.findByUsingsUserId(user.id)
        assertEquals(1, drugs.size)
        assertEquals(drug1.id, drugs[0].id)
    }

    @Test
    fun `DrugRepository - sumPlannedAmount returns sum of planned amounts`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        user2.medKits.add(medKit)
        medKit.users.add(user2)
        val drug = createDrug(medKit)
        entityManager.flush()

        val using1 = Using(
            usingKey = UsingKey(user1.id, drug.id),
            user = user1,
            drug = drug,
            plannedAmount = qty(20.0)
        )
        val using2 = Using(
            usingKey = UsingKey(user2.id, drug.id),
            user = user2,
            drug = drug,
            plannedAmount = qty(30.0)
        )
        usingRepository.save(using1)
        usingRepository.save(using2)
        entityManager.flush()
        entityManager.clear()
        assertQty(50.0, drugRepository.findByIdOrNull(drug.id)?.totalPlannedAmount)
    }

    @Test
    fun `DrugRepository - sumPlannedAmount returns 0 when no usings exist`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug = createDrug(medKit)
        entityManager.flush()
        entityManager.clear()

        assertQty(0.0, drug.totalPlannedAmount)
    }

    // === MedKitRepository Tests ===

    @Test
    fun `MedKitRepository - findByUsersId returns medkits for user`() {
        val user = createUser()
        val medKit1 = createMedKitForUser(user)
        val medKit2 = createMedKitForUser(user)
        entityManager.flush()
        entityManager.clear()

        val medKits = medKitRepository.findByUserId(user.id)
        assertEquals(2, medKits.size)
    }

    @Test
    fun `MedKitRepository - findByUsersId returns empty for user with no medkits`() {
        val user = createUser()
        entityManager.flush()

        val medKits = medKitRepository.findByUserId(user.id)
        assertTrue(medKits.isEmpty())
    }

    @Test
    fun `MedKitRepository - findByIdAndUserId returns medkit for authorized user`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        entityManager.flush()
        entityManager.clear()

        val found = medKitRepository.findByIdAndUserId(medKit.id, user.id)
        assertNotNull(found)
        assertEquals(medKit.id, found.id)
    }

    @Test
    fun `MedKitRepository - findByIdAndUserId returns null for unauthorized user`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        entityManager.flush()
        entityManager.clear()

        val found = medKitRepository.findByIdAndUserId(medKit.id, user2.id)
        assertNull(found)
    }

    @Test
    fun `MedKitRepository - findByIdWithDrugs eagerly loads drugs`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        createDrug(medKit, "Drug A")
        createDrug(medKit, "Drug B")
        entityManager.flush()
        entityManager.clear()

        val found = medKitRepository.findByIdWithDrugs(medKit.id)
        assertNotNull(found)
        assertEquals(2, found.drugs.size)
    }

    @Test
    fun `MedKitRepository - findByIdWithUsers eagerly loads users`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        user2.medKits.add(medKit)
        medKit.users.add(user2)
        entityManager.flush()
        entityManager.clear()

        val found = medKitRepository.findByIdWithUsers(medKit.id)
        assertNotNull(found)
        assertEquals(2, found.users.size)
    }

    // === UserRepository Tests ===

    @Test
    fun `UserRepository - findByMedKitsId returns users in medkit`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        user2.medKits.add(medKit)
        medKit.users.add(user2)
        entityManager.flush()
        entityManager.clear()

        val users = userRepository.findAllByMedKitsId(medKit.id)
        assertEquals(2, users.size)
    }

    @Test
    fun `UserRepository - findByUsingsDrugId returns users with treatment plans for drug`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        user2.medKits.add(medKit)
        medKit.users.add(user2)
        val drug = createDrug(medKit)
        entityManager.flush()

        val using = Using(
            usingKey = UsingKey(user1.id, drug.id),
            user = user1,
            drug = drug,
            plannedAmount = qty(10.0)
        )
        usingRepository.save(using)
        entityManager.flush()
        entityManager.clear()

        val users = userRepository.findByUsingsDrugId(drug.id)
        assertEquals(1, users.size)
        assertTrue(users.any { it.id == user1.id })
    }

    @Test
    fun `UserRepository - findByIdWithMedKits eagerly loads medkits`() {
        val user = createUser()
        createMedKitForUser(user)
        createMedKitForUser(user)
        entityManager.flush()
        entityManager.clear()

        val found = userRepository.findByIdWithMedKits(user.id)
        assertNotNull(found)
        assertEquals(2, found.medKits.size)
    }

    // === UsingRepository Tests ===

    @Test
    fun `UsingRepository - findAllByUserId returns usings for user`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug1 = createDrug(medKit, "Drug A")
        val drug2 = createDrug(medKit, "Drug B")
        entityManager.flush()

        usingRepository.save(Using(UsingKey(user.id, drug1.id), user, drug1, qty(10.0)))
        usingRepository.save(Using(UsingKey(user.id, drug2.id), user, drug2, qty(20.0)))
        entityManager.flush()
        entityManager.clear()

        val usings = usingRepository.findAllByUsingKeyUserId(user.id)
        assertEquals(2, usings.size)
    }

    @Test
    fun `UsingRepository - findAllByDrugId returns usings for drug`() {
        val user1 = createUser()
        val user2 = createUser()
        val medKit = createMedKitForUser(user1)
        user2.medKits.add(medKit)
        medKit.users.add(user2)
        val drug = createDrug(medKit)
        entityManager.flush()

        usingRepository.save(Using(UsingKey(user1.id, drug.id), user1, drug, qty(10.0)))
        usingRepository.save(Using(UsingKey(user2.id, drug.id), user2, drug, qty(20.0)))
        entityManager.flush()
        entityManager.clear()

        val usings = usingRepository.findAllByUsingKeyDrugId(drug.id)
        assertEquals(2, usings.size)
    }

    @Test
    fun `UsingRepository - findByUserIdAndDrugId returns specific using`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug = createDrug(medKit)
        entityManager.flush()

        usingRepository.save(Using(UsingKey(user.id, drug.id), user, drug, qty(15.0)))
        entityManager.flush()
        entityManager.clear()

        val using = usingRepository.findByUserIdAndDrugId(user.id, drug.id)
        assertNotNull(using)
        assertQty(15.0, using.plannedAmount)
    }

    @Test
    fun `UsingRepository - findByUserIdAndDrugId returns null when not found`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug = createDrug(medKit)
        entityManager.flush()

        val using = usingRepository.findByUserIdAndDrugId(user.id, drug.id)
        assertNull(using)
    }

    @Test
    fun `UsingRepository - findAllByUserIdWithDrug eagerly loads drug`() {
        val user = createUser()
        val medKit = createMedKitForUser(user)
        val drug = createDrug(medKit, "TestDrug")
        entityManager.flush()

        usingRepository.save(Using(UsingKey(user.id, drug.id), user, drug, qty(10.0)))
        entityManager.flush()
        entityManager.clear()

        val usings = usingRepository.findAllByUserIdWithDrug(user.id)
        assertEquals(1, usings.size)
        assertEquals("TestDrug", usings[0].drug.name)
    }
}
