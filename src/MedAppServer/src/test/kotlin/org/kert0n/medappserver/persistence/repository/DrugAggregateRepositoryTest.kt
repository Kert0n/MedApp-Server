package org.kert0n.medappserver.persistence.repository

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.Drug as DrugRecord
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.TreatmentPlan as TreatmentPlanRecord
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@PostgresIntegrationTest
@Transactional
class DrugAggregateRepositoryTest {
    @Autowired private lateinit var repository: DrugAggregateRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var planRepository: UsingRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `lock aggregate loads drug before ordered treatment plans`() {
        val fixture = fixture(planCount = 3)
        entityManager.flush()
        entityManager.clear()

        val aggregate = repository.lockAggregate(fixture.user.id, fixture.drug.id)

        assertNotNull(aggregate)
        assertEquals(fixture.drug.id, aggregate.drug.id)
        assertEquals(3, aggregate.plans.size)
        assertEquals(
            aggregate.plans.all().map { it.userId }.sortedBy(UUID::toString),
            aggregate.plans.all().map { it.userId }
        )
    }

    @Test
    fun `access predicate does not reveal another users drug`() {
        val fixture = fixture(planCount = 0)
        val stranger = userRepository.save(User(hashedKey = "stranger_${UUID.randomUUID()}"))
        entityManager.flush()

        assertNull(repository.findAccessible(stranger.id, fixture.drug.id))
    }

    @Test
    fun `domain changes are persisted through aggregate facade`() {
        val fixture = fixture(planCount = 2)
        entityManager.flush()
        entityManager.clear()
        val aggregate = requireNotNull(repository.lockAggregate(fixture.user.id, fixture.drug.id))

        val decision = aggregate.drug.consume(BigDecimal("4"), aggregate.plans)
        repository.update(aggregate.drug)
        repository.update(decision.changedPlans)

        assertEquals(
            BigDecimal("6.000000"),
            jdbc.queryForObject(
                "SELECT quantity FROM user_drugs WHERE id = ?",
                BigDecimal::class.java,
                fixture.drug.id
            )
        )
        assertEquals(
            BigDecimal("6.000000"),
            jdbc.queryForObject(
                "SELECT SUM(planned_amount) FROM usings WHERE drug_id = ?",
                BigDecimal::class.java,
                fixture.drug.id
            )
        )
    }

    @Test
    fun `database cascade deletes treatment plans with drug`() {
        val fixture = fixture(planCount = 5)
        entityManager.flush()
        entityManager.clear()

        assertEquals(1, repository.delete(fixture.drug.id))

        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usings WHERE drug_id = ?",
                Int::class.java,
                fixture.drug.id
            )
        )
    }

    @Test
    fun `bulk drug lock returns UUIDs in deterministic order`() {
        val fixture = fixture(planCount = 0)
        repeat(4) { index ->
            drugRepository.save(
                drugRecord(fixture.medKit, UUID.randomUUID(), "Drug $index")
            )
        }
        entityManager.flush()
        entityManager.clear()

        val locked = repository.lockAllByMedKitIds(listOf(fixture.medKit.id))

        assertEquals(locked.sortedBy(UUID::toString), locked)
    }

    private fun fixture(planCount: Int): Fixture {
        val owner = userRepository.save(User(hashedKey = "owner_${UUID.randomUUID()}"))
        val medKit = medKitRepository.save(MedKit())
        entityManager.flush()
        jdbc.update(
            "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES (?, ?)",
            owner.id, medKit.id
        )
        val drug = drugRepository.save(drugRecord(medKit, UUID.randomUUID(), "Drug"))

        repeat(planCount) { index ->
            val participant = if (index == 0) owner else userRepository.save(
                User(hashedKey = "participant_${index}_${UUID.randomUUID()}")
            )
            entityManager.flush()
            if (participant.id != owner.id) {
                jdbc.update(
                    "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES (?, ?)",
                    participant.id, medKit.id
                )
            }
            planRepository.save(
                TreatmentPlanRecord(
                    key = TreatmentPlanKey(participant.id, drug.id),
                    user = participant,
                    drug = drug,
                    plannedAmount = BigDecimal.TEN.divide(
                        BigDecimal(planCount.coerceAtLeast(1)),
                        6,
                        RoundingMode.DOWN
                    )
                )
            )
        }
        return Fixture(owner, medKit, drug)
    }

    private fun drugRecord(medKit: MedKit, id: UUID, name: String): DrugRecord = DrugRecord(
        id = id,
        name = name,
        quantity = BigDecimal.TEN,
        quantityUnit = "tablet",
        formType = null,
        category = null,
        manufacturer = null,
        country = null,
        description = null,
        medKit = medKit
    )

    private data class Fixture(
        val user: User,
        val medKit: MedKit,
        val drug: DrugRecord
    )
}
