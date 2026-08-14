package org.kert0n.medappserver.queryplan

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.nio.file.Path
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверяет рост числа обращений Hibernate к JDBC при увеличении fixture. */
@SpringBootTest
@Import(QueryPlanContainerConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NPlusOneTest {

    @Autowired private lateinit var fixture: QueryPlanFixture
    @Autowired private lateinit var statistics: HibernateStatisticsSupport
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitQueries: MedKitQueryService
    @Autowired private lateinit var vidalDrugService: VidalDrugService
    @Autowired private lateinit var drugCommands: DrugCommandService
    @Autowired private lateinit var medKitLifecycle: MedKitLifecycleService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tx: TransactionTemplate
    private lateinit var report: NPlusOneReport

    @BeforeAll
    fun prepare() {
        tx = TransactionTemplate(transactionManager)
        fixture.seed()
        report = NPlusOneReport(objectMapper)
    }

    @AfterAll
    fun writeReport() = report.write(Path.of("build/reports/query-plans"))

    private fun assertConstantSql(
        name: String,
        scenarios: Map<Int, () -> Unit>,
        expectedStatements: Int,
        flush: Boolean = false
    ): Map<Int, HibernateStatisticsSnapshot> {
        if (!flush) statistics.warmUp(scenario = scenarios.values.first())
        val measured = scenarios.mapValues { (_, scenario) ->
            statistics.measure(flush, scenario).statistics
        }
        measured.forEach { (size, value) ->
            assertEquals(
                expectedStatements.toLong(),
                value.preparedStatements,
                "$name, размер $size: $value"
            )
        }
        assertEquals(
            1,
            measured.values.map {
                Triple(it.queryExecutions, it.entityFetches, it.collectionFetches)
            }.distinct().size,
            "$name меняет Hibernate fetch/query-метрики при росте данных: $measured"
        )
        val owner = name.substringBefore('.')
        val method = name.substringAfter('.', name)
        measured.forEach { (size, value) ->
            report.record(NPlusOneMeasurement(owner, method, size, value, "Θ(1)"))
        }
        return measured
    }

    @Test
    fun `read модели не создают N плюс 1`() {
        assertConstantSql(
            "UsingService.listForUser",
            fixture.planUsers.mapValues { (size, userId) ->
                {
                    assertEquals(size, usingService.listForUser(userId).size)
                }
            },
            expectedStatements = 1
        )

        assertConstantSql(
            "MedKitQueryService.listForUser",
            fixture.snapshotUsers.mapValues { (size, userId) ->
                {
                    assertEquals(size, medKitQueries.listForUser(userId).size)
                }
            },
            expectedStatements = 1
        )

        val nonEmptySnapshots = fixture.snapshotUsers.filterKeys { it > 0 }
        assertConstantSql(
            "MedKitQueryService.getUserSnapshot",
            nonEmptySnapshots.mapValues { (size, userId) ->
                {
                    assertEquals(size, medKitQueries.getUserSnapshot(userId).medKits.size)
                }
            },
            expectedStatements = 2
        )
        val empty = statistics.measure {
            medKitQueries.getUserSnapshot(fixture.snapshotUsers.getValue(0))
        }
        assertTrue(empty.result.medKits.isEmpty())
        assertEquals(1, empty.statistics.preparedStatements)
        report.record(
            NPlusOneMeasurement(
                "MedKitQueryService",
                "getUserSnapshot empty",
                0,
                empty.statistics,
                "Θ(1)"
            )
        )

        assertConstantSql(
            "MedKitQueryService.getContent",
            fixture.contentMedKits.mapValues { (size, ids) ->
                {
                    assertEquals(size, medKitQueries.getContent(ids.first, ids.second).drugs.size)
                }
            },
            expectedStatements = 2
        )
    }

    @Test
    fun `каталог загружается фиксированным числом запросов`() {
        assertConstantSql(
            "VidalDrugService.fuzzySearch",
            listOf(1, 10, 50).associateWith { limit ->
                {
                    assertEquals(limit, vidalDrugService.fuzzySearch("таблетки", limit).size)
                }
            },
            expectedStatements = 3
        )
    }

    @Test
    fun `Drug чтение удаление и перенос не зависят от числа планов`() {
        assertConstantSql(
            "DrugService.getAccessible",
            listOf(0, 10, 100).associateWith { planCount ->
                val command = fixture.createDrugFixture(planCount)
                val scenario: () -> Unit = {
                    assertEquals(
                        command.drugId,
                        drugService.getAccessible(command.ownerId, command.drugId).id
                    )
                }
                scenario
            },
            expectedStatements = 1
        )

        assertConstantSql(
            "DrugCommandService.delete",
            listOf(0, 1, 100).associateWith { planCount ->
                val command = fixture.createDrugFixture(planCount)
                val scenario: () -> Unit = {
                    drugCommands.delete(command.ownerId, command.drugId)
                }
                scenario
            },
            expectedStatements = 2,
            flush = true
        )

        assertConstantSql(
            "DrugCommandService.move",
            listOf(1, 100).associateWith { planCount ->
                val command = fixture.createDrugFixture(planCount)
                val scenario: () -> Unit = {
                    drugCommands.move(command.ownerId, command.drugId, command.targetMedKitId)
                }
                scenario
            },
            expectedStatements = 5,
            flush = true
        )
    }

    @Test
    fun `исчерпание Drug использует каскад независимо от числа планов`() {
        val scenarios = listOf(0, 1, 100).associateWith { planCount ->
            val command = fixture.createDrugFixture(planCount)
            val scenario: () -> Unit = {
                drugCommands.consume(
                    command.ownerId,
                    command.drugId,
                    BigDecimal.valueOf(maxOf(planCount, 1) * 10L)
                )
            }
            scenario
        }
        assertConstantSql(
            "DrugCommandService.consume exhausted",
            scenarios,
            expectedStatements = 2,
            flush = true
        )
    }

    @Test
    fun `reconciliation добавляет только UPDATE изменившихся планов`() {
        val measured = listOf(2, 20).associateWith { planCount ->
            val command = fixture.createDrugFixture(planCount)
            statistics.measure(flush = true) {
                drugCommands.consume(
                    command.ownerId,
                    command.drugId,
                    BigDecimal.valueOf(planCount * 5L)
                )
            }.statistics
        }
        measured.forEach { (plans, value) ->
            assertEquals((plans + 3).toLong(), value.preparedStatements, "$plans планов: $value")
        }
        assertEquals(
            18,
            measured.getValue(20).preparedStatements - measured.getValue(2).preparedStatements
        )
        assertEquals(
            measured.getValue(2).queryExecutions,
            measured.getValue(20).queryExecutions,
            "число query executions должно оставаться постоянным"
        )
        measured.forEach { (size, value) ->
            report.record(
                NPlusOneMeasurement(
                    "DrugCommandService",
                    "consume reconciliation",
                    size,
                    value,
                    "Θ(n) DML; Θ(1) query/fetch"
                )
            )
        }
    }

    @Test
    fun `MedKit cascade не зависит от числа препаратов и планов`() {
        assertConstantSql(
            "MedKitLifecycleService.leave last member",
            listOf(1, 100).associateWith { size ->
                val command = fixture.createMedKitFixture(size, size, additionalMember = false)
                val scenario: () -> Unit = {
                    medKitLifecycle.leave(command.ownerId, command.medKitId)
                }
                scenario
            },
            expectedStatements = 5,
            flush = true
        )

        assertConstantSql(
            "MedKitLifecycleService.delete",
            listOf(1, 100).associateWith { size ->
                val command = fixture.createMedKitFixture(size, size, additionalMember = false)
                val scenario: () -> Unit = {
                    medKitLifecycle.delete(command.ownerId, command.medKitId)
                }
                scenario
            },
            expectedStatements = 3,
            flush = true
        )

        assertConstantSql(
            "MedKitLifecycleService.leave member",
            listOf(1, 100).associateWith { size ->
                val command = fixture.createMedKitFixture(size, size, additionalMember = true)
                val scenario: () -> Unit = {
                    medKitLifecycle.leave(command.ownerId, command.medKitId)
                }
                scenario
            },
            expectedStatements = 4,
            flush = true
        )
    }

    @Test
    fun `измерение не включает проверки состояния БД`() {
        val command = fixture.createDrugFixture(10)
        val measured = statistics.measure(flush = true) {
            drugCommands.delete(command.ownerId, command.drugId)
        }
        assertEquals(2, measured.statistics.preparedStatements)
        val remaining = tx.execute {
            entityManager.createNativeQuery(
                "SELECT count(*) FROM user_drugs WHERE id = '${command.drugId}'"
            ).singleResult.toString().toLong()
        }
        assertEquals(0, remaining)
    }
}
