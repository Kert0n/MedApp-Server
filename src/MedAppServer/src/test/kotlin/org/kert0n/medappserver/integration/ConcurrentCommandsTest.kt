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
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.error.DomainException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@PostgresIntegrationTest
class ConcurrentCommandsTest {
    @Autowired private lateinit var drugs: DrugOrchestrator
    @Autowired private lateinit var plans: TreatmentPlanOrchestrator
    @Autowired private lateinit var intakes: IntakeOrchestrator
    @Autowired private lateinit var medKits: MedKitOrchestrator
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `consume intake and plan patch serialize on drug lock`() {
        val fixture = sharedDrug()

        val errors = concurrently(
            { drugs.consume(fixture.owner, fixture.drugId, BigDecimal.TEN) },
            {
                intakes.record(
                    fixture.owner,
                    UUID.randomUUID(),
                    IntakePayload(fixture.drugId, BigDecimal.TEN)
                )
            },
            { plans.patch(fixture.participant, fixture.drugId, BigDecimal("30")) }
        )

        assertTrue(errors.isEmpty(), errors.joinToString { it.toString() })
        assertEquals(BigDecimal("80.000000"), decimal("SELECT quantity FROM user_drugs WHERE id = ?", fixture.drugId))
        assertEquals(
            BigDecimal("30.000000"),
            decimal("SELECT planned_amount FROM usings WHERE drug_id = ? AND user_id = ?", fixture.drugId, fixture.owner)
        )
        assertEquals(
            BigDecimal("30.000000"),
            decimal(
                "SELECT planned_amount FROM usings WHERE drug_id = ? AND user_id = ?",
                fixture.drugId,
                fixture.participant
            )
        )
    }

    @Test
    fun `concurrent retries with one intake id apply once`() {
        val owner = user()
        val medKit = medKits.create(owner)
        val drug = drugs.create(owner, drugCommand(medKit.id, BigDecimal.TEN))
        plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal.TEN))
        val intakeId = UUID.randomUUID()
        val payload = IntakePayload(drug.id, BigDecimal.ONE)

        val errors = concurrently(
            { intakes.record(owner, intakeId, payload) },
            { intakes.record(owner, intakeId, payload) }
        )

        assertTrue(errors.isEmpty(), errors.joinToString { it.toString() })
        assertEquals(BigDecimal("9.000000"), decimal("SELECT quantity FROM user_drugs WHERE id = ?", drug.id))
        assertEquals(BigDecimal("9.000000"), decimal("SELECT planned_amount FROM usings WHERE drug_id = ?", drug.id))
    }

    @Test
    fun `move and medkit delete finish without deadlock`() {
        val owner = user()
        val source = medKits.create(owner)
        val target = medKits.create(owner)
        val drug = drugs.create(owner, drugCommand(source.id, BigDecimal.TEN))

        val errors = concurrently(
            { drugs.move(owner, drug.id, target.id) },
            { medKits.delete(owner, source.id) }
        )

        assertTrue(errors.all { it is DomainException }, errors.joinToString { it.toString() })
        assertEquals(0, count("SELECT COUNT(*) FROM med_kits WHERE id = ?", source.id))
        val targetId = jdbc.query(
            "SELECT med_kit_id FROM user_drugs WHERE id = ?",
            { row, _ -> row.getObject(1, UUID::class.java) },
            drug.id
        ).singleOrNull()
        assertTrue(targetId == null || targetId == target.id)
    }

    private fun sharedDrug(): SharedDrug {
        val owner = user()
        val participant = user()
        val medKit = medKits.create(owner)
        medKits.join(participant, medKits.createInvitation(owner, medKit.id).key)
        val drug = drugs.create(owner, drugCommand(medKit.id, BigDecimal("100")))
        plans.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("40")))
        plans.create(participant, CreateTreatmentPlanCommand(drug.id, BigDecimal("40")))
        return SharedDrug(owner, participant, drug.id)
    }

    private fun user(): UUID = users.saveAndFlush(User(hashedKey = "concurrent_${UUID.randomUUID()}")).id

    private fun drugCommand(medKitId: UUID, quantity: BigDecimal) = CreateDrugCommand(
        medKitId = medKitId,
        name = "Concurrent drug",
        quantity = quantity,
        quantityUnit = "tablet"
    )

    private fun decimal(sql: String, vararg values: Any): BigDecimal =
        requireNotNull(jdbc.queryForObject(sql, BigDecimal::class.java, *values))

    private fun count(sql: String, vararg values: Any): Int =
        requireNotNull(jdbc.queryForObject(sql, Int::class.java, *values))

    private fun concurrently(vararg commands: () -> Any?): List<Throwable> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(commands.size)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        return try {
            val tasks = commands.map { command ->
                pool.submit {
                    start.await()
                    runCatching(command).onFailure(errors::add)
                }
            }
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            errors.toList()
        } finally {
            pool.shutdownNow()
        }
    }

    private data class SharedDrug(val owner: UUID, val participant: UUID, val drugId: UUID)
}
