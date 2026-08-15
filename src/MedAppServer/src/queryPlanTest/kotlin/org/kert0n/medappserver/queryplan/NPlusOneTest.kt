package org.kert0n.medappserver.queryplan

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.application.query.CatalogueQueryService
import org.kert0n.medappserver.application.query.DrugQueryService
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.nio.file.Path
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

/** Scaling gates. ORM is measured by Hibernate; direct JDBC at the DataSource boundary. */
@SpringBootTest
@Import(QueryPlanContainerConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NPlusOneTest {
    @Autowired private lateinit var fixture: QueryPlanFixture
    @Autowired private lateinit var hibernate: HibernateStatisticsSupport
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var drugs: DrugQueryService
    @Autowired private lateinit var plans: TreatmentPlanQueryService
    @Autowired private lateinit var medKits: MedKitQueryService
    @Autowired private lateinit var catalogue: CatalogueQueryService
    @Autowired private lateinit var drugCommands: DrugOrchestrator
    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var transaction: TransactionTemplate
    private lateinit var report: NPlusOneReport

    @BeforeAll
    fun prepare() {
        transaction = TransactionTemplate(transactionManager)
        fixture.seed()
        report = NPlusOneReport(objectMapper)
    }

    @AfterAll
    fun writeReport() = report.write(Path.of("build/reports/query-plans"))

    private fun assertJdbcShape(
        name: String,
        scenarios: Map<Int, () -> Int>,
        expectedStatements: (Int) -> Int
    ) {
        transaction.executeWithoutResult {
            entityManager.clear()
            scenarios.values.first().invoke()
            entityManager.clear()
        }
        val counts = scenarios.mapValues { (size, scenario) ->
            var resultSize = -1
            val orm = hibernate.measure {
                val statements = RecordingDataSource.capture { resultSize = scenario() }
                assertEquals(size, resultSize, "$name returned the wrong result size")
                statements.size
            }
            assertEquals(0, orm.statistics.preparedStatements, "$name bypasses ORM by contract")
            assertEquals(0, orm.statistics.entityFetches, "$name must not materialize JPA entities")
            assertEquals(0, orm.statistics.collectionFetches, "$name must not initialize collections")
            assertEquals(expectedStatements(size), orm.result, "$name, fixture size $size")
            report.record(
                NPlusOneMeasurement(
                    owner = name.substringBeforeLast('.'),
                    method = name.substringAfterLast('.'),
                    size = size,
                    statistics = orm.statistics,
                    jdbcStatements = orm.result,
                    complexity = "Θ(1)"
                )
            )
            orm.result
        }
        assertEquals(
            scenarios.keys.map(expectedStatements),
            counts.values.toList(),
            "$name changes SQL count with result size: $counts"
        )
    }

    @Test
    fun `JDBC read models keep a constant statement shape`() {
        assertJdbcShape(
            "TreatmentPlanQueryService.listForUser",
            fixture.planUsers.mapValues { (_, userId) -> { plans.listForUser(userId).size } },
            expectedStatements = { 1 }
        )
        assertJdbcShape(
            "MedKitQueryService.listForUser",
            fixture.snapshotUsers.mapValues { (_, userId) -> { medKits.listForUser(userId).size } },
            expectedStatements = { 1 }
        )
        assertJdbcShape(
            "MedKitQueryService.getUserSnapshot",
            fixture.snapshotUsers.mapValues { (_, userId) -> { medKits.getUserSnapshot(userId).medKits.size } },
            expectedStatements = { size -> if (size == 0) 1 else 2 }
        )
        assertJdbcShape(
            "MedKitQueryService.getContent",
            fixture.contentMedKits.mapValues { (_, ids) -> { medKits.getContent(ids.first, ids.second).drugs.size } },
            expectedStatements = { 2 }
        )
        assertJdbcShape(
            "DrugQueryService.getAccessible",
            fixture.drugsByPlanCount.mapValues { (planCount, ids) ->
                { drugs.getAccessible(ids.first, ids.second); planCount }
            },
            expectedStatements = { 1 }
        )
    }

    @Test
    fun `catalogue ORM loading remains three statements at every limit`() {
        hibernate.warmUp { catalogue.search("таблетки", 1) }
        val measurements = listOf(1, 10, 50).associateWith { limit ->
            hibernate.measure {
                catalogue.search("таблетки", limit).also {
                    assertEquals(limit, it.size, "catalogue limit $limit")
                }
            }.statistics
        }
        measurements.forEach { (limit, statistics) ->
            assertEquals(3, statistics.preparedStatements, "catalogue limit $limit: $statistics")
            assertEquals(0, statistics.collectionFetches, "catalogue limit $limit: $statistics")
            report.record(
                NPlusOneMeasurement(
                    "CatalogueQueryService", "search", limit, statistics, 0, "Θ(1)"
                )
            )
        }
        assertEquals(1, measurements.values.map { it.queryExecutions }.distinct().size)
        assertEquals(1, measurements.values.map { it.entityFetches }.distinct().size)
    }

    private fun measureJdbcWrite(
        owner: String,
        method: String,
        size: Int,
        complexity: String = "Θ(1)",
        scenario: () -> Unit
    ): Int {
        val measurement = hibernate.measure {
            RecordingDataSource.capture(scenario).size
        }
        assertEquals(0, measurement.statistics.preparedStatements)
        assertEquals(0, measurement.statistics.entityFetches)
        assertEquals(0, measurement.statistics.collectionFetches)
        report.record(
            NPlusOneMeasurement(owner, method, size, measurement.statistics, measurement.result, complexity)
        )
        return measurement.result
    }

    @Test
    fun `cascade writes do not grow with treatment plan count`() {
        val deleteCounts = listOf(0, 1, 100).associateWith { planCount ->
            val command = fixture.createDrugCommandFixture(planCount)
            measureJdbcWrite("DrugOrchestrator", "delete", planCount) {
                drugCommands.delete(command.ownerId, command.drugId)
            }
        }
        assertEquals(setOf(2), deleteCounts.values.toSet(), "delete SQL grew: $deleteCounts")

        val exhaustedCounts = listOf(0, 1, 100).associateWith { planCount ->
            val command = fixture.createDrugCommandFixture(planCount)
            measureJdbcWrite("DrugOrchestrator", "consume.exhausted", planCount) {
                drugCommands.consume(command.ownerId, command.drugId, BigDecimal(command.stock))
            }
        }
        assertEquals(setOf(3), exhaustedCounts.values.toSet(), "exhaustion SQL grew: $exhaustedCounts")
    }

    @Test
    fun `reconciliation adds only one update per changed plan`() {
        val statements = listOf(2, 20).associateWith { planCount ->
            val command = fixture.createDrugCommandFixture(planCount)
            measureJdbcWrite("DrugOrchestrator", "consume.reconciliation", planCount, "Θ(n) plan UPDATE") {
                val remaining = planCount * 5
                drugCommands.consume(
                    command.ownerId,
                    command.drugId,
                    BigDecimal(command.stock - remaining)
                )
            }
        }
        assertEquals(5, statements.getValue(2))
        assertEquals(23, statements.getValue(20))
        assertEquals(18, statements.getValue(20) - statements.getValue(2))
    }
}
