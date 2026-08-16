package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.model.IntakePayload
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.query.DrugQueryService
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.error.DrugNotFound
import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
import org.kert0n.medappserver.domain.error.TreatmentPlanNotFound
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@PostgresIntegrationTest
class UserStoryRegressionTest {
    @Autowired private lateinit var drugs: DrugOrchestrator
    @Autowired private lateinit var plans: TreatmentPlanOrchestrator
    @Autowired private lateinit var intakes: IntakeOrchestrator
    @Autowired private lateinit var medKits: MedKitOrchestrator
    @Autowired private lateinit var drugQueries: DrugQueryService
    @Autowired private lateinit var planQueries: TreatmentPlanQueryService
    @Autowired private lateinit var medKitQueries: MedKitQueryService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `shared medicine kit workflow is visible in both snapshots`() {
        val owner = user()
        val participant = user()
        val medKit = medKits.create(owner)
        medKits.join(participant, medKits.createInvitation(owner, medKit.id).key)
        val drug = drugs.create(owner, drugCommand(medKit.id, "12"))
        plans.create(participant, CreateTreatmentPlanCommand(drug.id, BigDecimal("5")))

        val intake = intakes.record(
            participant,
            UUID.randomUUID(),
            IntakePayload(drug.id, BigDecimal("2"))
        )

        assertEquals(BigDecimal("10.000000"), intake.drug?.quantity)
        assertEquals(BigDecimal("3.000000"), intake.plan?.plannedAmount)
        assertEquals(drug.id, medKitQueries.getUserSnapshot(owner).medKits.single().drugs.single().id)
        assertEquals(drug.id, medKitQueries.getUserSnapshot(participant).medKits.single().drugs.single().id)
    }

    @Test
    fun `resources outside membership are indistinguishable from missing ones`() {
        val owner = user()
        val stranger = user()
        val medKit = medKits.create(owner)
        val drug = drugs.create(owner, drugCommand(medKit.id, "10"))
        plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("2")))

        assertFailsWith<DrugNotFound> { drugQueries.getAccessible(stranger, drug.id) }
        assertFailsWith<TreatmentPlanNotFound> { planQueries.getForUser(stranger, drug.id) }
        assertFailsWith<MedKitNotFound> { medKitQueries.getContent(stranger, medKit.id) }
        assertFailsWith<DrugNotFound> { drugs.consume(stranger, drug.id, BigDecimal.ONE) }

        assertEquals(BigDecimal("10.000000"), decimal("SELECT quantity FROM user_drugs WHERE id = ?", drug.id))
    }

    @Test
    fun `treatment plan duplicate conflicts and deletion is explicit`() {
        val owner = user()
        val medKit = medKits.create(owner)
        val drug = drugs.create(owner, drugCommand(medKit.id, "10"))
        plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("4")))

        assertFailsWith<TreatmentPlanAlreadyExists> {
            plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("1")))
        }
        plans.delete(owner, drug.id)

        assertFailsWith<TreatmentPlanNotFound> { planQueries.getForUser(owner, drug.id) }
        assertEquals(0, count("SELECT COUNT(*) FROM usings WHERE drug_id = ?", drug.id))
    }

    @Test
    fun `exhausting stock removes drug and plans through database cascade`() {
        val owner = user()
        val medKit = medKits.create(owner)
        val drug = drugs.create(owner, drugCommand(medKit.id, "1"))
        plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal.ONE))

        val result = drugs.consume(owner, drug.id, BigDecimal.ONE)

        assertNull(result)
        assertEquals(0, count("SELECT COUNT(*) FROM user_drugs WHERE id = ?", drug.id))
        assertEquals(0, count("SELECT COUNT(*) FROM usings WHERE drug_id = ?", drug.id))
    }

    private fun user(): UUID = users.saveAndFlush(User(hashedKey = "story_${UUID.randomUUID()}")).id

    private fun drugCommand(medKitId: UUID, quantity: String) = CreateDrugCommand(
        medKitId = medKitId,
        name = "Story drug",
        quantity = BigDecimal(quantity),
        quantityUnit = "tablet"
    )

    private fun decimal(sql: String, vararg values: Any): BigDecimal =
        requireNotNull(jdbc.queryForObject(sql, BigDecimal::class.java, *values))

    private fun count(sql: String, vararg values: Any): Int =
        requireNotNull(jdbc.queryForObject(sql, Int::class.java, *values))
}
