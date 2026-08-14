package org.kert0n.medappserver.integration

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@PostgresIntegrationTest
@Transactional
class UseCaseRepositoryTest {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var medKits: MedKitRepository
    @Autowired private lateinit var drugs: DrugRepository
    @Autowired private lateinit var plans: UsingRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `access queries expose only members and return target members`() {
        val owner = user()
        val outsider = user()
        val medKit = medKit(owner)
        val drug = drug(medKit)
        entityManager.flush()
        entityManager.clear()

        assertNotNull(drugs.findAccessible(drug.id, owner.id))
        assertNull(drugs.findAccessible(drug.id, outsider.id))
        assertNotNull(medKits.findAccessible(medKit.id, owner.id))
        assertNull(medKits.findAccessible(medKit.id, outsider.id))
        assertEquals(setOf(owner.id), medKits.findMemberIds(medKit.id))
        assertEquals(1, medKits.countMembers(medKit.id))
    }

    @Test
    fun `move removes ineligible plans with one bulk command`() {
        val owner = user()
        val sourceMember = user()
        val source = medKit(owner, sourceMember)
        val target = medKit(owner)
        val drug = drug(source)
        plan(owner, drug)
        plan(sourceMember, drug)
        entityManager.flush()
        entityManager.clear()

        assertEquals(1, plans.deleteByDrugIdAndUserIdNotIn(drug.id, setOf(owner.id)))
        assertEquals(1, drugs.moveToMedKit(drug.id, target.id))

        assertEquals(listOf(owner.id), plans.findAllByDrugId(drug.id).map { it.usingKey.userId })
        assertEquals(target.id, drugs.findById(drug.id).orElseThrow().medKit.id)
    }

    @Test
    fun `deleting medkit in SQL cascades memberships drugs and plans`() {
        val owner = user()
        val medKit = medKit(owner)
        val drug = drug(medKit)
        plan(owner, drug)
        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("DELETE FROM med_kits WHERE id = :id")
            .setParameter("id", medKit.id)
            .executeUpdate()

        assertEquals(0L, count("user_med_kits", "med_kit_id", medKit.id))
        assertEquals(0L, count("user_drugs", "med_kit_id", medKit.id))
        assertEquals(0L, count("usings", "drug_id", drug.id))
    }

    private fun user(): User =
        users.save(User(hashedKey = "{noop}repo-${UUID.randomUUID()}"))

    private fun medKit(vararg members: User): MedKit {
        val medKit = medKits.save(MedKit())
        members.forEach {
            it.medKits.add(medKit)
            medKit.users.add(it)
        }
        return medKit
    }

    private fun drug(medKit: MedKit): Drug =
        drugs.save(
            Drug(
                name = "Repo drug",
                quantity = qty(100.0),
                quantityUnit = "таб",
                formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null,
                medKit = medKit
            )
        )

    private fun plan(user: User, drug: Drug): Using =
        plans.save(
            Using(
                usingKey = UsingKey(user.id, drug.id),
                user = user,
                drug = drug,
                plannedAmount = qty(10.0)
            )
        )

    private fun count(table: String, column: String, id: UUID): Long =
        (entityManager.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :id")
            .setParameter("id", id)
            .singleResult as Number).toLong()
}
