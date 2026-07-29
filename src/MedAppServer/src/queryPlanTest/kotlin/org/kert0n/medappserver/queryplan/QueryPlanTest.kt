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
 * Проверяет постоянную форму SQL и индексные планы на большом PostgreSQL fixture.
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
        val plans = executed.filter { it.isSelect }
            .distinctBy { it.fingerprint }
            .mapNotNull { statement ->
                explain(explainConnection, objectMapper, statement, forceIndexes)
                    ?.let { statement.sql to it }
            }
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

    private fun assertNoSql(name: String, scenario: () -> Unit) {
        val statements = RecordingDataSource.capture(scenario)
        assertTrue(statements.isEmpty(), "$name должен выполняться без SQL:\n${statements.diagnostic()}")
        report.record(
            QueryMeasurement(
                owner = name.substringBefore('.'),
                method = name.substringAfter('.', name),
                branch = "zero-sql",
                size = null,
                result = "0 SQL подтверждено",
                statements = emptyList(),
                complexity = "0 SQL"
            )
        )
    }

    private fun record(
        owner: String,
        method: String,
        branch: String,
        size: Int? = null,
        result: String,
        statements: List<ExecutedStatement>,
        complexity: String = "Θ(1)",
        forceIndexes: Boolean = true
    ) {
        report.record(
            QueryMeasurement(
                owner = owner,
                method = method,
                branch = branch,
                size = size,
                result = result,
                statements = statements,
                plans = explainStatements(statements, forceIndexes).map { it.second },
                complexity = complexity
            )
        )
    }

    private fun explainStatements(
        statements: List<ExecutedStatement>,
        forceIndexes: Boolean = false
    ): List<Pair<String, QueryPlan>> =
        statements.distinctBy { it.fingerprint }.mapNotNull { statement ->
            explain(explainConnection, objectMapper, statement, forceIndexes)
                ?.let { statement.sql to it }
        }

    private fun scalarLong(sql: String): Long =
        tx.execute {
            entityManager.createNativeQuery(sql).singleResult.toString().toLong()
        }!!

    private fun scalarDecimal(sql: String): BigDecimal =
        tx.execute {
            BigDecimal(entityManager.createNativeQuery(sql).singleResult.toString())
        }!!

    private fun assertConstantQueryShape(
        name: String,
        scenarios: Map<Int, () -> Unit>,
        expected: Map<SqlKind, Int>
    ) {
        val measured = scenarios.mapValues { (_, scenario) -> capture(scenario).queryShape() }
        println("\n=== $name ===")
        measured.forEach { (size, shape) -> println("  размер $size: ${shape.byKind}") }

        measured.forEach { (size, shape) ->
            expected.forEach { (kind, count) ->
                assertEquals(count, shape.count(kind), "$name, размер $size, $kind")
            }
        }
        val fingerprints = measured.values.map(QueryShape::fingerprints)
        assertTrue(
            fingerprints.distinct().size == 1,
            "$name меняет форму SQL с размером данных:\n$measured"
        )
    }

    private fun assertNoSeqScanOn(table: String, plans: List<Pair<String, QueryPlan>>) {
        val offenders = plans.filter { table in it.second.sequentiallyScanned }
        assertTrue(
            offenders.isEmpty(),
            "последовательное сканирование $table:\n" +
                offenders.joinToString("\n") { "  ${it.second}\n    ${it.first.take(200)}" }
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

    /** Snapshot имеет одинаковый SQL-бюджет при любом числе аптечек. */
    @Test
    fun `выдача пользователю не делает запрос на каждую аптечку`() {
        assertConstantQueryShape(
            name = "GET /v1/users/me",
            scenarios = fixture.snapshotUsers.mapValues { (_, userId) ->
                {
                    val result = medKitQueries.getUserSnapshot(userId)
                    assertEquals(
                        fixture.snapshotUsers.entries.single { it.value == userId }.key,
                        result.medKits.size
                    )
                }
            },
            expected = mapOf(SqlKind.SELECT to 2)
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

    /** Список планов читается одним оператором независимо от размера результата. */
    @Test
    fun `список планов пользователя не делает запрос на каждый план`() {
        assertConstantQueryShape(
            name = "GET /v1/treatment-plans",
            scenarios = fixture.planUsers.mapValues { (plans, userId) ->
                {
                    val result = usingService.listForUser(userId)
                    assertEquals(plans, result.size, "фикстура обязана вернуть $plans планов")
                }
            },
            expected = mapOf(SqlKind.SELECT to 1)
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
        val executed = capture {
            val result = usingService.getForUser(userId, drugId)
            assertEquals(drugId, result.drugId)
        }
        assertEquals(mapOf(SqlKind.SELECT to 1), executed.queryShape().byKind)
        val plans = explainStatements(executed, forceIndexes = true)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `содержимое аптечки имеет два SELECT при любом размере`() {
        assertConstantQueryShape(
            name = "GET /v1/med-kits/{id}",
            scenarios = fixture.contentMedKits.mapValues { (size, ids) ->
                {
                    val result = medKitQueries.getContent(ids.first, ids.second)
                    assertEquals(size, result.drugs.size)
                }
            },
            expected = mapOf(SqlKind.SELECT to 2)
        )
        val ids = fixture.contentMedKits.getValue(100)
        val plans = plansOf("GET /v1/med-kits/{id}", forceIndexes = true) {
            medKitQueries.getContent(ids.first, ids.second)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `доступный препарат читается одним SELECT`() {
        val executed = capture {
            val result = drugService.getAccessible(fixture.ownerId, fixture.drugId)
            assertEquals(fixture.drugId, result.id)
        }
        assertEquals(mapOf(SqlKind.SELECT to 1), executed.queryShape().byKind)
        val plans = explainStatements(executed, forceIndexes = true)
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
    fun `перенос препарата имеет постоянную форму и bulk удаление планов`() {
        val measured = listOf(1, 100).associateWith { plans ->
            val command = fixture.createDrugFixture(plans)
            val executed = captureWrite {
                drugCommands.move(command.ownerId, command.drugId, command.targetMedKitId)
            }
            assertEquals(
                command.targetMedKitId.toString(),
                tx.execute {
                    entityManager.createNativeQuery(
                        "SELECT med_kit_id FROM user_drugs WHERE id = '${command.drugId}'"
                    ).singleResult.toString()
                }
            )
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${command.drugId}'"))
            executed
        }

        measured.forEach { (size, statements) ->
            val shape = statements.queryShape()
            assertEquals(3, shape.count(SqlKind.SELECT), "move, $size plans")
            assertEquals(1, shape.count(SqlKind.DELETE), "move, $size plans")
            assertEquals(1, shape.count(SqlKind.UPDATE), "move, $size plans")
        }
        assertEquals(
            measured.getValue(1).map { it.fingerprint }.toSet(),
            measured.getValue(100).map { it.fingerprint }.toSet()
        )

        val plans = explainStatements(measured.getValue(100), forceIndexes = true)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `удаление препарата не зависит от числа планов`() {
        listOf(1, 100).forEach { planCount ->
            val command = fixture.createDrugFixture(planCount)
            val shape = captureWrite {
                drugCommands.delete(command.ownerId, command.drugId)
            }.queryShape()
            assertEquals(1, shape.count(SqlKind.SELECT), "delete, $planCount plans")
            assertEquals(1, shape.count(SqlKind.DELETE), "delete, $planCount plans")
            assertEquals(0, scalarLong("SELECT count(*) FROM user_drugs WHERE id = '${command.drugId}'"))
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${command.drugId}'"))
        }
    }

    @Test
    fun `согласование добавляет только UPDATE изменившихся планов`() {
        val measured = listOf(2, 20).associateWith { planCount ->
            val command = fixture.createDrugFixture(planCount)
            val statements = captureWrite {
                drugCommands.consume(
                    command.ownerId,
                    command.drugId,
                    BigDecimal.valueOf(planCount * 5L)
                )
            }
            assertEquals(
                0,
                scalarDecimal(
                    "SELECT quantity - COALESCE((SELECT sum(planned_amount) FROM usings " +
                        "WHERE drug_id = '${command.drugId}'), 0) " +
                        "FROM user_drugs WHERE id = '${command.drugId}'"
                ).compareTo(BigDecimal.ZERO)
            )
            statements
        }

        measured.forEach { (plans, statements) ->
            val shape = statements.queryShape()
            assertEquals(2, shape.count(SqlKind.SELECT), "reconcile, $plans plans")
            assertEquals(plans + 1, shape.count(SqlKind.UPDATE), "reconcile, $plans plans")
        }
        assertEquals(
            18,
            measured.getValue(20).queryShape().count(SqlKind.UPDATE) -
                measured.getValue(2).queryShape().count(SqlKind.UPDATE)
        )
        assertEquals(
            measured.getValue(2).filter { it.kind == SqlKind.SELECT }.map { it.fingerprint }.toSet(),
            measured.getValue(20).filter { it.kind == SqlKind.SELECT }.map { it.fingerprint }.toSet()
        )
        val plans = explainStatements(measured.getValue(20), forceIndexes = true)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `исчерпанный препарат удаляется каскадом без SQL на каждый план`() {
        val shapes = listOf(1, 100).associateWith { planCount ->
            val command = fixture.createDrugFixture(planCount)
            val stock = BigDecimal.valueOf(maxOf(planCount, 1) * 10L)
            val shape = captureWrite {
                drugCommands.consume(command.ownerId, command.drugId, stock)
            }.queryShape()
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${command.drugId}'"))
            shape
        }
        shapes.values.forEach { shape ->
            assertEquals(1, shape.count(SqlKind.SELECT))
            assertEquals(1, shape.count(SqlKind.DELETE))
            assertEquals(0, shape.count(SqlKind.UPDATE))
        }
    }

    @Test
    fun `lifecycle аптечки использует постоянные bulk команды`() {
        val lastLeave = listOf(1, 100).associateWith { size ->
            val command = fixture.createMedKitFixture(size, size, additionalMember = false)
            val statements = captureWrite {
                medKitLifecycle.leave(command.ownerId, command.medKitId)
            }
            assertEquals(0, scalarLong("SELECT count(*) FROM med_kits WHERE id = '${command.medKitId}'"))
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE user_id = '${command.ownerId}'"))
            statements
        }
        lastLeave.values.map { it.queryShape() }.forEach { shape ->
            assertEquals(3, shape.count(SqlKind.SELECT))
            assertEquals(2, shape.count(SqlKind.DELETE))
        }

        val explicitDelete = listOf(1, 100).associateWith { size ->
            val command = fixture.createMedKitFixture(size, size, additionalMember = false)
            val statements = captureWrite {
                medKitLifecycle.delete(command.ownerId, command.medKitId)
            }
            assertEquals(0, scalarLong("SELECT count(*) FROM med_kits WHERE id = '${command.medKitId}'"))
            statements
        }
        explicitDelete.values.map { it.queryShape() }.forEach { shape ->
            assertEquals(2, shape.count(SqlKind.SELECT))
            assertEquals(1, shape.count(SqlKind.DELETE))
        }

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
            lastLeave.getValue(100) + explicitDelete.getValue(100) + nonLastStatements,
            forceIndexes = true
        )
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
