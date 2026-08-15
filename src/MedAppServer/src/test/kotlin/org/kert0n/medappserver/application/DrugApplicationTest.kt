package org.kert0n.medappserver.application

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.model.IntakePayload
import org.kert0n.medappserver.application.model.PatchDrugCommand
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.error.IntakeConflict
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@PostgresIntegrationTest
class DrugApplicationTest {
    @Autowired private lateinit var drugOrchestrator: DrugOrchestrator
    @Autowired private lateinit var treatmentPlanOrchestrator: TreatmentPlanOrchestrator
    @Autowired private lateinit var intakeOrchestrator: IntakeOrchestrator
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `write service refuses to create its own transaction`() {
        val fixture = fixture()

        assertFailsWith<IllegalTransactionStateException> {
            drugService.create(createCommand(fixture.sourceMedKitId))
        }
    }

    @Test
    fun `drug orchestrator creates patches consumes and deletes aggregate`() {
        val fixture = fixture()
        val created = drugOrchestrator.create(fixture.ownerId, createCommand(fixture.sourceMedKitId))
        val plan = treatmentPlanOrchestrator.create(
            fixture.ownerId,
            CreateTreatmentPlanCommand(created.id, BigDecimal("6"))
        )

        val patched = drugOrchestrator.patch(
            fixture.ownerId,
            created.id,
            PatchDrugCommand(name = "Corrected", quantity = BigDecimal("12"))
        )
        val consumed = drugOrchestrator.consume(fixture.ownerId, created.id, BigDecimal("7"))

        assertEquals(BigDecimal("12.000000"), patched.quantity)
        assertEquals(BigDecimal("5.000000"), consumed?.quantity)
        assertEquals(BigDecimal("4.999999"), consumed?.plannedQuantity)
        assertEquals(BigDecimal("6.000000"), plan.plannedAmount)

        drugOrchestrator.delete(fixture.ownerId, created.id)
        assertEquals(0, count("user_drugs", "id", created.id))
        assertEquals(0, count("usings", "drug_id", created.id))
    }

    @Test
    fun `failed plan change rolls transaction back`() {
        val fixture = fixture()
        val drug = drugOrchestrator.create(fixture.ownerId, createCommand(fixture.sourceMedKitId))
        treatmentPlanOrchestrator.create(
            fixture.ownerId,
            CreateTreatmentPlanCommand(drug.id, BigDecimal("4"))
        )

        assertFailsWith<PlannedAmountExceedsStock> {
            treatmentPlanOrchestrator.patch(fixture.ownerId, drug.id, BigDecimal("11"))
        }

        assertEquals(
            BigDecimal("4.000000"),
            jdbc.queryForObject(
                "SELECT planned_amount FROM usings WHERE user_id = ? AND drug_id = ?",
                BigDecimal::class.java,
                fixture.ownerId,
                drug.id
            )
        )
    }

    @Test
    fun `move preserves only plans of target members`() {
        val fixture = fixture(withSecondUser = true)
        val drug = drugOrchestrator.create(fixture.ownerId, createCommand(fixture.sourceMedKitId))
        treatmentPlanOrchestrator.create(
            fixture.ownerId,
            CreateTreatmentPlanCommand(drug.id, BigDecimal("3"))
        )
        treatmentPlanOrchestrator.create(
            requireNotNull(fixture.secondUserId),
            CreateTreatmentPlanCommand(drug.id, BigDecimal("2"))
        )

        val moved = drugOrchestrator.move(fixture.ownerId, drug.id, fixture.targetMedKitId)

        assertEquals(fixture.targetMedKitId, moved.medKitId)
        assertEquals(0, count("usings", "user_id", fixture.secondUserId))
        assertEquals(1, count("usings", "user_id", fixture.ownerId))
    }

    @Test
    fun `intake caches only committed result and detects conflicting retry`() {
        val fixture = fixture()
        val drug = drugOrchestrator.create(fixture.ownerId, createCommand(fixture.sourceMedKitId))
        treatmentPlanOrchestrator.create(
            fixture.ownerId,
            CreateTreatmentPlanCommand(drug.id, BigDecimal("3"))
        )
        val intakeId = UUID.randomUUID()
        val payload = IntakePayload(drug.id, BigDecimal("1"))

        val first = intakeOrchestrator.record(fixture.ownerId, intakeId, payload)
        val repeated = intakeOrchestrator.record(fixture.ownerId, intakeId, payload)

        assertEquals(first, repeated)
        assertEquals(BigDecimal("2.000000"), repeated.plan?.plannedAmount)
        assertFailsWith<IntakeConflict> {
            intakeOrchestrator.record(
                fixture.ownerId,
                intakeId,
                IntakePayload(drug.id, BigDecimal("2"))
            )
        }
        assertEquals(
            BigDecimal("9.000000"),
            jdbc.queryForObject(
                "SELECT quantity FROM user_drugs WHERE id = ?",
                BigDecimal::class.java,
                drug.id
            )
        )
    }

    @Test
    fun `failed intake is not cached`() {
        val fixture = fixture()
        val drug = drugOrchestrator.create(fixture.ownerId, createCommand(fixture.sourceMedKitId))
        treatmentPlanOrchestrator.create(
            fixture.ownerId,
            CreateTreatmentPlanCommand(drug.id, BigDecimal("2"))
        )
        val intakeId = UUID.randomUUID()

        assertFailsWith<PlannedAmountExceedsStock> {
            intakeOrchestrator.record(
                fixture.ownerId,
                intakeId,
                IntakePayload(drug.id, BigDecimal("3"))
            )
        }

        val retried = intakeOrchestrator.record(
            fixture.ownerId,
            intakeId,
            IntakePayload(drug.id, BigDecimal("2"))
        )
        assertNull(retried.plan)
    }

    @Test
    fun `invalid create fails before insert`() {
        val fixture = fixture()

        assertFailsWith<InvalidQuantity> {
            drugOrchestrator.create(
                fixture.ownerId,
                createCommand(fixture.sourceMedKitId).copy(quantity = BigDecimal.ZERO)
            )
        }
        assertEquals(0, count("user_drugs", "med_kit_id", fixture.sourceMedKitId))
    }

    private fun fixture(withSecondUser: Boolean = false): Fixture =
        TransactionTemplate(transactionManager).execute {
            val owner = userRepository.save(User(hashedKey = "owner_${UUID.randomUUID()}"))
            val second = if (withSecondUser) {
                userRepository.save(User(hashedKey = "second_${UUID.randomUUID()}"))
            } else {
                null
            }
            val source = medKitRepository.save(MedKit())
            val target = medKitRepository.save(MedKit())
            owner.medKits.addAll(listOf(source, target))
            source.users.add(owner)
            target.users.add(owner)
            second?.let {
                it.medKits.add(source)
                source.users.add(it)
            }
            entityManager.flush()
            Fixture(owner.id, second?.id, source.id, target.id)
        }!!

    private fun createCommand(medKitId: UUID): CreateDrugCommand = CreateDrugCommand(
        medKitId = medKitId,
        name = "Drug",
        quantity = BigDecimal.TEN,
        quantityUnit = "tablet"
    )

    private fun count(table: String, column: String, id: UUID?): Int = requireNotNull(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM $table WHERE $column = ?",
            Int::class.java,
            id
        )
    )

    private data class Fixture(
        val ownerId: UUID,
        val secondUserId: UUID?,
        val sourceMedKitId: UUID,
        val targetMedKitId: UUID
    )
}
