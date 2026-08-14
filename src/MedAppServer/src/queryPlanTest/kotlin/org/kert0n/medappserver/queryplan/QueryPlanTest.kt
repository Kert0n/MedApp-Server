package org.kert0n.medappserver.queryplan

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверяет SQL и индексные планы на большом PostgreSQL fixture.
 * Набор запускается отдельно:
 *
 *     ./gradlew queryPlanTest
 */
@SpringBootTest
@Import(QueryPlanContainerConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanTest {

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var fixture: QueryPlanFixture
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var usingRepository: UsingRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitQueries: MedKitQueryService
    @Autowired private lateinit var drugCommands: DrugCommandService
    @Autowired private lateinit var medKitLifecycle: MedKitLifecycleService
    @Autowired private lateinit var vidalDrugService: VidalDrugService
    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var container: PostgreSQLContainer

    private lateinit var tx: TransactionTemplate
    private lateinit var explainConnection: Connection
    private lateinit var report: QueryPlanReport

    @BeforeAll
    fun prepare() {
        tx = TransactionTemplate(transactionManager)
        fixture.seed()
        explainConnection = openExplainConnection(container)
        report = QueryPlanReport(objectMapper)
    }

    @AfterAll
    fun tearDown() {
        report.write(Path.of("build/reports/query-plans"))
        explainConnection.close()
    }

    /** Собирает планы уникальных SELECT измеряемого сценария. */
    private fun plansOf(
        name: String,
        forceIndexes: Boolean = false,
        scenario: () -> Unit
    ): List<Pair<String, QueryPlan>> {
        val executed = capture(scenario)
        val unique = executed.filter { it.isSelect }.distinctBy { it.fingerprint }
        val naturalPlans = unique.map { statement ->
            statement.sql to explain(explainConnection, objectMapper, statement)
        }
        val forcedPlans = if (forceIndexes) {
            unique.map { statement ->
                statement.sql to explain(explainConnection, objectMapper, statement, true)
            }
        } else {
            emptyList()
        }
        val plans = forcedPlans.ifEmpty { naturalPlans }
        println("\n=== $name: операторов ${executed.size}, разобрано планов ${plans.size} ===")
        executed.groupingBy { it.sql }.eachCount()
            .entries.sortedByDescending { it.value }.take(3)
            .forEach { (sql, times) ->
                if (times > 1) println("  ПОВТОР x$times: ${sql.take(120).replace(Regex("\\s+"), " ")}")
            }
        plans.forEach { (sql, plan) ->
            val scans = plan.sequentiallyScanned
            val note = if (scans.isEmpty()) "" else "   [Seq Scan: ${scans.joinToString()}]"
            println("  $plan$note\n    ${sql.take(110).replace(Regex("\\s+"), " ")}")
        }
        report.record(
            QueryMeasurement(
                owner = "Repository SQL",
                method = name,
                branch = if (forceIndexes) "forced indexes" else "natural plan",
                size = null,
                result = "${executed.size} операторов, ${plans.size} уникальных планов",
                statements = executed,
                naturalPlans = naturalPlans.map { it.second },
                forcedPlans = forcedPlans.map { it.second },
                complexity = "EXPLAIN"
            )
        )
        return plans
    }

    private fun capture(scenario: () -> Unit): List<ExecutedStatement> {
        tx.executeWithoutResult {
            scenario()
            entityManager.clear()
        }
        return RecordingDataSource.capture {
            tx.executeWithoutResult {
                scenario()
                entityManager.clear()
            }
        }.also { it.queryShape() }
    }

    private fun captureWrite(scenario: () -> Unit): List<ExecutedStatement> =
        RecordingDataSource.capture {
            tx.executeWithoutResult {
                entityManager.clear()
                scenario()
                entityManager.flush()
                entityManager.clear()
            }
        }.also { it.queryShape() }

    private fun explainStatements(
        statements: List<ExecutedStatement>,
        forceIndexes: Boolean = false
    ): List<Pair<String, QueryPlan>> =
        statements.distinctBy { it.fingerprint }.map { statement ->
            statement.sql to explain(explainConnection, objectMapper, statement, forceIndexes)
        }

    private fun scalarLong(sql: String): Long =
        tx.execute {
            entityManager.createNativeQuery(sql).singleResult.toString().toLong()
        }!!

    private fun assertNoSeqScanOn(table: String, plans: List<Pair<String, QueryPlan>>) {
        val offenders = plans.filter { table in it.second.sequentiallyScanned }
        assertTrue(
            offenders.isEmpty(),
            "последовательное сканирование $table:\n" +
                offenders.joinToString("\n") { "  ${it.second}\n    ${it.first.take(200)}" }
        )
    }

    private fun recordWritePlan(
        name: String,
        statements: List<ExecutedStatement>,
        plans: List<Pair<String, QueryPlan>>
    ) {
        val naturalPlans = explainStatements(statements)
        report.record(
            QueryMeasurement(
                owner = "Repository SQL",
                method = name,
                branch = "write plan",
                size = null,
                result = statements.queryShape().toString(),
                statements = statements,
                naturalPlans = naturalPlans.map { it.second },
                forcedPlans = plans.map { it.second },
                complexity = "EXPLAIN"
            )
        )
    }

    /** Поиск должен иметь индексный план для каждого обращения к каталогу. */
    @Test
    fun `поиск по справочнику умеет пользоваться триграммными индексами`() {
        val scenario: () -> Unit = { vidalDrugService.fuzzySearch(fixture.catalogueName, 10) }
        val executed = capture(scenario)
        assertEquals(3, executed.size, "поиск по каталогу должен сохранять фиксированный бюджет")
        val plans = plansOf("поиск по каталогу", forceIndexes = true, scenario = scenario)

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.startsWith("ix_parsed_drugs_") },
            "поиск обязан уметь задействовать индексы каталога, а использованы: $used"
        )
    }

    /** Snapshot допускает индексный доступ к препаратам и планам. */
    @Test
    fun `выдача пользователю умеет читать препараты и планы по индексам`() {
        val plans = plansOf("GET /v1/users/me", forceIndexes = true) {
            medKitQueries.getUserSnapshot(fixture.ownerId)
        }

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.contains("med_kit_id") } && used.any { it.contains("usings") },
            "выборка обязана уметь идти по индексам препаратов и планов, а использованы: $used"
        )
    }

    @Test
    fun `список планов пользователя идёт по индексу`() {
        val plans = plansOf("GET /v1/treatment-plans", forceIndexes = true) {
            usingService.listForUser(fixture.ownerId)
        }
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `адресный план читается одним SELECT`() {
        val userId = fixture.planUsers.getValue(1)
        val drugId = fixture.planDrugs.getValue(1)
        val plans = plansOf("GET /v1/treatment-plans/{drugId}", forceIndexes = true) {
            val result = usingService.getForUser(userId, drugId)
            assertEquals(drugId, result.drugId)
        }
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `содержимое аптечки использует индексы`() {
        val ids = fixture.contentMedKits.getValue(100)
        val plans = plansOf("GET /v1/med-kits/{id}", forceIndexes = true) {
            medKitQueries.getContent(ids.first, ids.second)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `доступный препарат использует индексы`() {
        val plans = plansOf("GET /v1/drugs/{id}", forceIndexes = true) {
            drugService.getAccessible(fixture.ownerId, fixture.drugId)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `выборка препарата под блокировку берёт индекс и запирает строку`() {
        val plans = plansOf("lock + load", forceIndexes = true) {
            drugRepository.findAccessibleForUpdate(fixture.drugId, fixture.ownerId)
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertTrue(
            plans.any { "LockRows" in it.second.nodeTypes },
            "в плане обязан быть LockRows: на этой блокировке держится защита от гонки. " +
                "Планы: ${plans.map { it.second }}"
        )
    }

    @Test
    fun `препарат с планами берётся по индексу`() {
        val plans = plansOf("drug with plans", forceIndexes = true) {
            drugService.getAccessible(fixture.ownerId, fixture.drugId)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `перенос препарата объясняет bulk SQL`() {
        val command = fixture.createDrugFixture(100)
        val statements = captureWrite {
            drugCommands.move(command.ownerId, command.drugId, command.targetMedKitId)
        }
        val shape = statements.queryShape()
        assertEquals(3, shape.count(SqlKind.SELECT))
        assertEquals(1, shape.count(SqlKind.DELETE))
        assertEquals(1, shape.count(SqlKind.UPDATE))
        val plans = explainStatements(statements, forceIndexes = true)
        recordWritePlan("move drug", statements, plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `согласование объясняет SELECT и UPDATE`() {
        val planCount = 20
        val command = fixture.createDrugFixture(planCount)
        val statements = captureWrite {
            drugCommands.consume(
                command.ownerId,
                command.drugId,
                BigDecimal.valueOf(planCount * 5L)
            )
        }
        val shape = statements.queryShape()
        assertEquals(2, shape.count(SqlKind.SELECT))
        assertEquals(planCount + 1, shape.count(SqlKind.UPDATE))
        val plans = explainStatements(statements, forceIndexes = true)
        recordWritePlan("reconcile plans", statements, plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `lifecycle аптечки объясняет bulk команды`() {
        val last = fixture.createMedKitFixture(100, 100, additionalMember = false)
        val lastLeave = captureWrite {
            medKitLifecycle.leave(last.ownerId, last.medKitId)
        }
        assertEquals(3, lastLeave.queryShape().count(SqlKind.SELECT))
        assertEquals(1, lastLeave.queryShape().count(SqlKind.DELETE))

        val explicit = fixture.createMedKitFixture(100, 100, additionalMember = false)
        val explicitDelete = captureWrite {
            medKitLifecycle.delete(explicit.ownerId, explicit.medKitId)
        }
        assertEquals(2, explicitDelete.queryShape().count(SqlKind.SELECT))
        assertEquals(1, explicitDelete.queryShape().count(SqlKind.DELETE))

        val nonLast = fixture.createMedKitFixture(100, 100, additionalMember = true)
        val nonLastStatements = captureWrite {
            medKitLifecycle.leave(nonLast.ownerId, nonLast.medKitId)
        }
        val nonLastShape = nonLastStatements.queryShape()
        assertEquals(2, nonLastShape.count(SqlKind.SELECT))
        assertEquals(2, nonLastShape.count(SqlKind.DELETE))
        assertEquals(1, scalarLong("SELECT count(*) FROM med_kits WHERE id = '${nonLast.medKitId}'"))
        assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE user_id = '${nonLast.ownerId}'"))

        val plans = explainStatements(
            lastLeave + explicitDelete + nonLastStatements,
            forceIndexes = true
        )
        recordWritePlan("medkit lifecycle", lastLeave + explicitDelete + nonLastStatements, plans)
        assertNoSeqScanOn("med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `удаление аптечки с переносом использует bulk UPDATE и bulk DELETE`() {
        val command = fixture.createMedKitFixture(
            100,
            100,
            additionalMember = false,
            ownerPlans = false
        )
        val targetMedKitId = fixture.createTransferTarget(command)
        val statements = captureWrite {
            medKitLifecycle.delete(command.ownerId, command.medKitId, targetMedKitId)
        }
        val shape = statements.queryShape()
        assertEquals(3, shape.count(SqlKind.SELECT))
        assertEquals(2, shape.count(SqlKind.DELETE))
        assertEquals(1, shape.count(SqlKind.UPDATE))
        assertEquals(0, scalarLong("SELECT count(*) FROM med_kits WHERE id = '${command.medKitId}'"))
        assertEquals(
            100,
            scalarLong("SELECT count(*) FROM user_drugs WHERE med_kit_id = '$targetMedKitId'")
        )
        assertEquals(
            0,
            scalarLong(
                "SELECT count(*) FROM usings u JOIN user_drugs d ON d.id = u.drug_id " +
                    "WHERE d.med_kit_id = '$targetMedKitId'"
            )
        )

        val plans = explainStatements(statements, forceIndexes = true)
        recordWritePlan("delete medkit with transfer", statements, plans)
        assertNoSeqScanOn("med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `summary аптечек использует индексы memberships и drugs`() {
        val plans = plansOf("GET /v1/med-kits", forceIndexes = true) {
            val result = medKitQueries.listForUser(fixture.ownerId)
            assertTrue(result.isNotEmpty())
        }
        assertNoSeqScanOn("med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }
}
