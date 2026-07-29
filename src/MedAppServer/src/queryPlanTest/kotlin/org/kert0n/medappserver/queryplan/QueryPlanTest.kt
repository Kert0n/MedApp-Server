package org.kert0n.medappserver.queryplan

import com.sksamuel.aedile.core.Cache
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.services.models.DrugPatch
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.IntakeOutcome
import org.kert0n.medappserver.services.orchestrators.IntakeService
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.kert0n.medappserver.services.orchestrators.PlanReconciler
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.services.security.SecurityService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.math.BigDecimal
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    @Autowired private lateinit var treatmentPlans: TreatmentPlanService
    @Autowired private lateinit var intakeService: IntakeService
    @Autowired private lateinit var medKitLifecycle: MedKitLifecycleService
    @Autowired private lateinit var vidalDrugService: VidalDrugService
    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var securityService: SecurityService
    @Autowired @Qualifier("intakeResultsCache")
    private lateinit var intakeResultsCache: Cache<String, IntakeOutcome>
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
            .map { statement ->
                statement.sql to explain(explainConnection, objectMapper, statement, forceIndexes)
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

    private fun <T : Throwable> captureFailure(
        expected: Class<T>,
        scenario: () -> Unit
    ): Pair<List<ExecutedStatement>, T> {
        lateinit var failure: Throwable
        val statements = RecordingDataSource.capture {
            try {
                tx.executeWithoutResult {
                    entityManager.clear()
                    scenario()
                    entityManager.flush()
                }
                error("Ожидалось ${expected.simpleName}")
            } catch (error: Throwable) {
                failure = error
            } finally {
                entityManager.clear()
            }
        }
        assertTrue(
            expected.isInstance(failure),
            "Ожидалось ${expected.name}, получено ${failure::class.java.name}: ${failure.message}"
        )
        @Suppress("UNCHECKED_CAST")
        return statements to failure as T
    }

    private fun captureNoSqlFailure(name: String, scenario: () -> Unit) {
        var failure: Throwable? = null
        val statements = RecordingDataSource.capture {
            failure = runCatching(scenario).exceptionOrNull()
        }
        assertNotNull(failure, "$name должен завершиться ошибкой")
        assertTrue(statements.isEmpty(), "$name должен выполняться без SQL:\n${statements.diagnostic()}")
        report.record(
            QueryMeasurement(
                owner = name.substringBefore('.'),
                method = name.substringAfter('.').substringBefore(' '),
                branch = name.substringAfter(' ', "failure"),
                size = null,
                result = "ошибка до обращения к БД",
                statements = emptyList(),
                complexity = "0 SQL"
            )
        )
    }

    private fun assertNoSql(name: String, scenario: () -> Unit) {
        val statements = RecordingDataSource.capture(scenario)
        assertTrue(statements.isEmpty(), "$name должен выполняться без SQL:\n${statements.diagnostic()}")
        val qualifiedName = name.substringBefore(' ')
        report.record(
            QueryMeasurement(
                owner = qualifiedName.substringBefore('.'),
                method = qualifiedName.substringAfter('.', qualifiedName),
                branch = name.substringAfter(' ', "zero-sql"),
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
                naturalPlans = explainStatements(statements).map { it.second },
                forcedPlans = if (forceIndexes) {
                    explainStatements(statements.filter(ExecutedStatement::isSelect), true)
                        .map { it.second }
                } else {
                    emptyList()
                },
                complexity = complexity
            )
        )
    }

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

    private fun scalarDecimal(sql: String): BigDecimal =
        tx.execute {
            BigDecimal(entityManager.createNativeQuery(sql).singleResult.toString())
        }!!

    private fun assertConstantQueryShape(
        name: String,
        scenarios: Map<Int, () -> Unit>,
        expected: Map<SqlKind, Int>
    ): Map<Int, List<ExecutedStatement>> {
        val statements = scenarios.mapValues { (_, scenario) -> capture(scenario) }
        val measured = statements.mapValues { it.value.queryShape() }
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
        return statements
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
        record(
            "VidalDrugService", "fuzzySearch", "10 результатов", 10,
            "3 оператора, индексный поиск", executed
        )
    }

    /** Snapshot имеет одинаковый SQL-бюджет при любом числе аптечек. */
    @Test
    fun `выдача пользователю не делает запрос на каждую аптечку`() {
        val measured = assertConstantQueryShape(
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
        measured.forEach { (size, statements) ->
            record(
                "MedKitQueryService", "getUserSnapshot", "snapshot", size,
                "$size аптечек", statements
            )
        }
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
        val measured = assertConstantQueryShape(
            name = "GET /v1/treatment-plans",
            scenarios = fixture.planUsers.mapValues { (plans, userId) ->
                {
                    val result = usingService.listForUser(userId)
                    assertEquals(plans, result.size, "фикстура обязана вернуть $plans планов")
                }
            },
            expected = mapOf(SqlKind.SELECT to 1)
        )
        measured.forEach { (size, statements) ->
            record(
                "UsingService", "listForUser", "список", size,
                "$size планов", statements
            )
        }
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
        record(
            "UsingService", "getForUser", "существующий план", 1,
            "план найден", executed
        )
    }

    @Test
    fun `содержимое аптечки имеет два SELECT при любом размере`() {
        val measured = assertConstantQueryShape(
            name = "GET /v1/med-kits/{id}",
            scenarios = fixture.contentMedKits.mapValues { (size, ids) ->
                {
                    val result = medKitQueries.getContent(ids.first, ids.second)
                    assertEquals(size, result.drugs.size)
                }
            },
            expected = mapOf(SqlKind.SELECT to 2)
        )
        measured.forEach { (size, statements) ->
            record(
                "MedKitQueryService", "getContent", "доступная аптечка", size,
                "$size препаратов", statements
            )
        }
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
        record(
            "DrugService", "getAccessible", "доступный препарат", 1,
            "DrugView", executed
        )
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
            record(
                "DrugCommandService", "move", "bulk перенос", size,
                "перенесён, лишние планы удалены", statements
            )
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
            val statements = captureWrite {
                drugCommands.delete(command.ownerId, command.drugId)
            }
            val shape = statements.queryShape()
            assertEquals(1, shape.count(SqlKind.SELECT), "delete, $planCount plans")
            assertEquals(1, shape.count(SqlKind.DELETE), "delete, $planCount plans")
            assertEquals(0, scalarLong("SELECT count(*) FROM user_drugs WHERE id = '${command.drugId}'"))
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${command.drugId}'"))
            record(
                "DrugCommandService", "delete", "каскадное удаление", planCount,
                "Drug и планы удалены", statements
            )
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
            record(
                "DrugCommandService", "consume", "reconciliation", plans,
                "$plans планов согласовано", statements, complexity = "Θ(n) Using UPDATE"
            )
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
            val statements = captureWrite {
                drugCommands.consume(command.ownerId, command.drugId, stock)
            }
            assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${command.drugId}'"))
            record(
                "DrugCommandService", "consume", "исчерпание", planCount,
                "Drug удалён каскадом", statements
            )
            statements.queryShape()
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
            assertEquals(1, shape.count(SqlKind.DELETE))
        }
        lastLeave.forEach { (size, statements) ->
            record(
                "MedKitLifecycleService", "leave", "последний участник", size,
                "аптечка удалена каскадом", statements
            )
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
        explicitDelete.forEach { (size, statements) ->
            record(
                "MedKitLifecycleService", "delete", "без переноса", size,
                "аптечка удалена каскадом", statements
            )
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
        record(
            "MedKitLifecycleService", "leave", "не последний участник", 100,
            "планы и membership удалены bulk", nonLastStatements
        )

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
        record(
            "MedKitLifecycleService", "delete", "с переносом", 100,
            "100 препаратов перенесено bulk", statements
        )

        val plans = explainStatements(statements, forceIndexes = true)
        assertNoSeqScanOn("med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertNoSeqScanOn("user_med_kits", plans)
    }

    @Test
    fun `read сервисы покрывают пустые и отсутствующие результаты`() {
        assertNoSql("VidalDrugService.fuzzySearch blank") {
            assertTrue(vidalDrugService.fuzzySearch("   ", 10).isEmpty())
        }

        listOf(1, 10, 50).forEach { limit ->
            val statements = capture {
                val result = vidalDrugService.fuzzySearch("таблетки", limit)
                assertEquals(limit, result.size)
            }
            assertEquals(3, statements.size, "catalogue limit=$limit")
            record(
                "VidalDrugService", "fuzzySearch",
                if (limit == 1) "один результат" else "непустой поиск",
                limit,
                "$limit результатов", statements
            )
        }

        val foundTemplate = capture {
            assertEquals(fixture.catalogueId, vidalDrugService.findById(fixture.catalogueId).id)
        }
        assertEquals(1, foundTemplate.size, "адресная карточка должна загружаться одним JOIN SELECT")
        record(
            "VidalDrugService", "findById", "существующий шаблон", 1,
            "шаблон найден", foundTemplate
        )

        val missingId = UUID.randomUUID()
        val missingTemplate = capture {
            assertNull(vidalDrugService.findByIdOrNull(missingId))
        }
        assertEquals(1, missingTemplate.size)
        record(
            "VidalDrugService", "findByIdOrNull", "отсутствующий шаблон", 0,
            "null", missingTemplate
        )

        val emptyPlans = capture {
            assertTrue(usingService.listForUser(fixture.emptyUserId).isEmpty())
        }
        record(
            "UsingService", "listForUser", "пустой список", 0,
            "0 планов", emptyPlans
        )

        val emptySnapshot = capture {
            assertTrue(medKitQueries.getUserSnapshot(fixture.emptyUserId).medKits.isEmpty())
        }
        assertEquals(1, emptySnapshot.queryShape().count(SqlKind.SELECT))
        record(
            "MedKitQueryService", "getUserSnapshot", "нет аптечек", 0,
            "ранний выход после 1 SELECT", emptySnapshot
        )

        val emptySummary = capture {
            assertTrue(medKitQueries.listForUser(fixture.emptyUserId).isEmpty())
        }
        record(
            "MedKitQueryService", "listForUser", "нет аптечек", 0,
            "0 summary", emptySummary
        )

        val (missingPlan, _) = captureFailure(ResponseStatusException::class.java) {
            usingService.getForUser(fixture.emptyUserId, UUID.randomUUID())
        }
        record(
            "UsingService", "getForUser", "план отсутствует", 0,
            "404 после адресного SELECT", missingPlan
        )

        val (missingDrug, _) = captureFailure(ResponseStatusException::class.java) {
            drugService.getAccessible(fixture.emptyUserId, UUID.randomUUID())
        }
        record(
            "DrugService", "getAccessible", "Drug отсутствует", 0,
            "404 после access SELECT", missingDrug
        )

        val (missingKit, _) = captureFailure(ResponseStatusException::class.java) {
            medKitQueries.getContent(fixture.emptyUserId, UUID.randomUUID())
        }
        record(
            "MedKitQueryService", "getContent", "аптечка недоступна", 0,
            "404 без загрузки Drug", missingKit
        )
    }

    @Test
    fun `read сервисы сохраняют форму на дополнительных размерах и ветках`() {
        listOf(0, 10, 100).forEach { planCount ->
            val command = fixture.createDrugFixture(planCount)
            val statements = capture {
                assertEquals(
                    command.drugId,
                    drugService.getAccessible(command.ownerId, command.drugId).id
                )
            }
            assertEquals(1, statements.size)
            record(
                "DrugService", "getAccessible", "масштаб планов", planCount,
                "один DrugView", statements
            )
        }

        fixture.snapshotUsers.forEach { (kitCount, userId) ->
            val statements = capture {
                assertEquals(kitCount, medKitQueries.listForUser(userId).size)
            }
            assertEquals(1, statements.size)
            record(
                "MedKitQueryService", "listForUser", "масштаб summary", kitCount,
                "$kitCount summary", statements
            )
        }

        val noMatches = capture {
            assertTrue(
                vidalDrugService.fuzzySearch("no-match-${UUID.randomUUID()}", 50).isEmpty()
            )
        }
        assertEquals(1, noMatches.size)
        record(
            "VidalDrugService", "fuzzySearch", "совпадений нет", 0,
            "только основной SELECT", noMatches
        )

        val nullableFound = capture {
            assertEquals(
                fixture.catalogueId,
                vidalDrugService.findByIdOrNull(fixture.catalogueId)?.id
            )
        }
        record(
            "VidalDrugService", "findByIdOrNull", "существующий шаблон", 1,
            "шаблон найден", nullableFound
        )

        val (missingRequired, _) = captureFailure(ResponseStatusException::class.java) {
            vidalDrugService.findById(UUID.randomUUID())
        }
        record(
            "VidalDrugService", "findById", "шаблон отсутствует", 0,
            "404 после PK SELECT", missingRequired
        )

        val foundUser = capture {
            assertEquals(fixture.ownerId, userService.findById(fixture.ownerId).id)
        }
        record(
            "UserService", "findById", "пользователь найден", 1,
            "User", foundUser
        )

        val missingLogin = UUID.randomUUID()
        val statements = RecordingDataSource.capture {
            assertFailsWith<org.springframework.security.core.userdetails.UsernameNotFoundException> {
                userService.loadUserByUsername(missingLogin.toString())
            }
        }
        record(
            "UserService", "loadUserByUsername", "UUID отсутствует", 0,
            "UsernameNotFound после PK SELECT", statements
        )
    }

    @Test
    fun `UserService имеет явные SQL и zero SQL ветки`() {
        val loaded = capture {
            assertEquals(fixture.ownerId, userService.loadUserByUsername(fixture.ownerId.toString()).username.let(UUID::fromString))
        }
        record(
            "UserService", "loadUserByUsername", "существующий UUID", 1,
            "UserDetails", loaded
        )

        captureNoSqlFailure("UserService.loadUserByUsername malformed") {
            userService.loadUserByUsername("not-a-uuid")
        }

        val missingId = UUID.randomUUID()
        val (missing, _) = captureFailure(ResponseStatusException::class.java) {
            userService.findById(missingId)
        }
        record(
            "UserService", "findById", "пользователь отсутствует", 0,
            "404 после PK SELECT", missing
        )

        val ip = "query-plan-${UUID.randomUUID()}"
        val registered = captureWrite {
            assertNotNull(userService.registerNewUser(ip).login)
        }
        assertEquals(1, registered.queryShape().count(SqlKind.INSERT))
        record(
            "UserService", "registerNewUser", "успешная регистрация", 1,
            "User INSERT", registered
        )
        captureNoSqlFailure("UserService.registerNewUser rate-limit") {
            userService.registerNewUser(ip)
        }
    }

    @Test
    fun `Drug команды покрывают no-op ошибки и обычные записи`() {
        captureNoSqlFailure("DrugCommandService.create non-positive") {
            drugCommands.create(
                fixture.emptyUserId,
                UUID.randomUUID(),
                DrugCreation("invalid", BigDecimal.ZERO, "таб")
            )
        }
        captureNoSqlFailure("DrugCommandService.consume non-positive") {
            drugCommands.consume(fixture.emptyUserId, UUID.randomUUID(), BigDecimal.ZERO)
        }

        val kit = fixture.createMedKitFixture(0, 0, additionalMember = false)
        val created = captureWrite {
            drugCommands.create(
                kit.ownerId,
                kit.medKitId,
                DrugCreation("Measured", BigDecimal.TEN, "таб")
            )
        }
        record(
            "DrugCommandService", "create", "успех", 1,
            "Drug создан", created
        )

        val noOpFixture = fixture.createDrugFixture(0)
        val noOp = captureWrite {
            drugCommands.patch(noOpFixture.ownerId, noOpFixture.drugId, DrugPatch())
        }
        assertEquals(0, noOp.queryShape().count(SqlKind.UPDATE))
        record(
            "DrugCommandService", "patch", "пустой PATCH", 0,
            "только lock SELECT", noOp
        )

        val patchFixture = fixture.createDrugFixture(0)
        val patched = captureWrite {
            drugCommands.patch(
                patchFixture.ownerId,
                patchFixture.drugId,
                DrugPatch(name = "Renamed", quantity = BigDecimal.valueOf(20))
            )
        }
        assertEquals(1, patched.queryShape().count(SqlKind.UPDATE))
        record(
            "DrugCommandService", "patch", "изменение и увеличение", 1,
            "один dirty-check UPDATE", patched
        )

        val invalidPatchFixture = fixture.createDrugFixture(0)
        val (invalidPatch, error) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.patch(
                invalidPatchFixture.ownerId,
                invalidPatchFixture.drugId,
                DrugPatch(quantity = BigDecimal.TEN)
            )
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        record(
            "DrugCommandService", "patch", "quantity не увеличен", 0,
            "rollback после lock", invalidPatch
        )

        val consumeFixture = fixture.createDrugFixture(0)
        val consumed = captureWrite {
            drugCommands.consume(consumeFixture.ownerId, consumeFixture.drugId, BigDecimal.ONE)
        }
        assertEquals(1, consumed.queryShape().count(SqlKind.UPDATE))
        record(
            "DrugCommandService", "consume", "обычное списание", 0,
            "остаток обновлён без загрузки планов", consumed
        )

        val sameTarget = fixture.createDrugFixture(0)
        val sameMove = captureWrite {
            drugCommands.move(sameTarget.ownerId, sameTarget.drugId, sameTarget.sourceMedKitId)
        }
        assertEquals(mapOf(SqlKind.SELECT to 1), sameMove.queryShape().byKind)
        record(
            "DrugCommandService", "move", "та же аптечка", 0,
            "идемпотентный ранний выход", sameMove
        )

        val mixed = fixture.createDrugFixture(100, targetMemberCount = 50)
        val mixedMove = captureWrite {
            drugCommands.move(mixed.ownerId, mixed.drugId, mixed.targetMedKitId)
        }
        assertEquals(
            50,
            scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${mixed.drugId}'")
        )
        record(
            "DrugCommandService", "move", "сохранение доступных планов", 100,
            "50 сохранено, 50 удалено одним DELETE", mixedMove
        )
    }

    @Test
    fun `Drug команды записывают SQL веток доступа и отказа`() {
        val inaccessibleKit = fixture.createMedKitFixture(0, 0, additionalMember = false)
        val (createDenied, _) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.create(
                fixture.emptyUserId,
                inaccessibleKit.medKitId,
                DrugCreation("denied", BigDecimal.TEN, "таб")
            )
        }
        record(
            "DrugCommandService", "create", "аптечка недоступна", 0,
            "404, INSERT отсутствует", createDenied
        )

        val (patchMissing, _) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.patch(fixture.emptyUserId, UUID.randomUUID(), DrugPatch(name = "x"))
        }
        record(
            "DrugCommandService", "patch", "Drug отсутствует", 0,
            "404 после lock SELECT", patchMissing
        )

        val consumeFixture = fixture.createDrugFixture(0)
        val (overStock, _) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.consume(
                consumeFixture.ownerId,
                consumeFixture.drugId,
                BigDecimal.valueOf(11)
            )
        }
        record(
            "DrugCommandService", "consume", "превышение остатка", 0,
            "rollback после lock SELECT", overStock
        )

        val moveFixture = fixture.createDrugFixture(0)
        val (targetMissing, _) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.move(moveFixture.ownerId, moveFixture.drugId, UUID.randomUUID())
        }
        record(
            "DrugCommandService", "move", "цель недоступна", 0,
            "2 SELECT, DML отсутствует", targetMissing
        )

        val (deleteMissing, _) = captureFailure(ResponseStatusException::class.java) {
            drugCommands.delete(fixture.emptyUserId, UUID.randomUUID())
        }
        record(
            "DrugCommandService", "delete", "Drug отсутствует", 0,
            "404 после lock SELECT", deleteMissing
        )
    }

    @Test
    fun `TreatmentPlan команды покрывают жизненный цикл плана`() {
        captureNoSqlFailure("TreatmentPlanService.create non-positive") {
            treatmentPlans.create(fixture.emptyUserId, UUID.randomUUID(), BigDecimal.ZERO)
        }
        captureNoSqlFailure("TreatmentPlanService.patch non-positive") {
            treatmentPlans.patch(fixture.emptyUserId, UUID.randomUUID(), BigDecimal.ZERO)
        }
        captureNoSqlFailure("TreatmentPlanService.applyIntake non-positive") {
            treatmentPlans.applyIntake(fixture.emptyUserId, UUID.randomUUID(), BigDecimal.ZERO)
        }

        val createFixture = fixture.createDrugFixture(0)
        val createStatements = captureWrite {
            treatmentPlans.create(createFixture.ownerId, createFixture.drugId, BigDecimal.valueOf(5))
        }
        record(
            "TreatmentPlanService", "create", "успех", 1,
            "Using создан", createStatements
        )

        val (duplicate, duplicateError) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.create(createFixture.ownerId, createFixture.drugId, BigDecimal.ONE)
        }
        assertEquals(HttpStatus.CONFLICT, duplicateError.statusCode)
        record(
            "TreatmentPlanService", "create", "дубликат", 1,
            "409, INSERT отсутствует", duplicate
        )

        val patchFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(patchFixture.ownerId, patchFixture.drugId, BigDecimal.valueOf(4))
        }
        val patchStatements = captureWrite {
            treatmentPlans.patch(patchFixture.ownerId, patchFixture.drugId, BigDecimal.valueOf(6))
        }
        record(
            "TreatmentPlanService", "patch", "увеличение", 1,
            "один Using UPDATE", patchStatements
        )

        val deleteFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(deleteFixture.ownerId, deleteFixture.drugId, BigDecimal.valueOf(4))
        }
        val deleteStatements = captureWrite {
            treatmentPlans.delete(deleteFixture.ownerId, deleteFixture.drugId)
        }
        assertEquals(0, scalarLong("SELECT count(*) FROM usings WHERE drug_id = '${deleteFixture.drugId}'"))
        record(
            "TreatmentPlanService", "delete", "успех", 1,
            "Using удалён", deleteStatements
        )

        val intakeFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(intakeFixture.ownerId, intakeFixture.drugId, BigDecimal.valueOf(5))
        }
        val remaining = captureWrite {
            assertEquals(
                0,
                treatmentPlans.applyIntake(
                    intakeFixture.ownerId,
                    intakeFixture.drugId,
                    BigDecimal.valueOf(2)
                )!!.plannedAmount.compareTo(BigDecimal.valueOf(3))
            )
        }
        record(
            "TreatmentPlanService", "applyIntake", "план остаётся", 1,
            "Drug и Using обновлены", remaining
        )

        val zeroPlanFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(zeroPlanFixture.ownerId, zeroPlanFixture.drugId, BigDecimal.valueOf(5))
        }
        val zeroPlan = captureWrite {
            assertNull(
                treatmentPlans.applyIntake(
                    zeroPlanFixture.ownerId,
                    zeroPlanFixture.drugId,
                    BigDecimal.valueOf(5)
                )
            )
        }
        record(
            "TreatmentPlanService", "applyIntake", "план обнулён", 1,
            "Using удалён, Drug обновлён", zeroPlan
        )

        val exhaustFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(exhaustFixture.ownerId, exhaustFixture.drugId, BigDecimal.TEN)
        }
        val exhaust = captureWrite {
            assertNull(
                treatmentPlans.applyIntake(
                    exhaustFixture.ownerId,
                    exhaustFixture.drugId,
                    BigDecimal.TEN
                )
            )
        }
        assertEquals(0, scalarLong("SELECT count(*) FROM user_drugs WHERE id = '${exhaustFixture.drugId}'"))
        record(
            "TreatmentPlanService", "applyIntake", "Drug исчерпан", 1,
            "Drug и план удалены каскадом", exhaust
        )
    }

    @Test
    fun `TreatmentPlan команды записывают SQL отказов`() {
        val inaccessible = fixture.createDrugFixture(0)
        val (accessDenied, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.create(fixture.emptyUserId, inaccessible.drugId, BigDecimal.ONE)
        }
        record(
            "TreatmentPlanService", "create", "Drug недоступен", 0,
            "404 после lock SELECT", accessDenied
        )

        val insufficientFixture = fixture.createDrugFixture(0)
        val (insufficientCreate, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.create(
                insufficientFixture.ownerId,
                insufficientFixture.drugId,
                BigDecimal.valueOf(11)
            )
        }
        record(
            "TreatmentPlanService", "create", "недостаточно остатка", 0,
            "2 SELECT, INSERT отсутствует", insufficientCreate
        )

        val missingPlanFixture = fixture.createDrugFixture(0)
        val (missingPatch, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.patch(
                missingPlanFixture.ownerId,
                missingPlanFixture.drugId,
                BigDecimal.ONE
            )
        }
        record(
            "TreatmentPlanService", "patch", "план отсутствует", 0,
            "2 SELECT, UPDATE отсутствует", missingPatch
        )

        val insufficientPatchFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(
                insufficientPatchFixture.ownerId,
                insufficientPatchFixture.drugId,
                BigDecimal.valueOf(5)
            )
        }
        val (insufficientPatch, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.patch(
                insufficientPatchFixture.ownerId,
                insufficientPatchFixture.drugId,
                BigDecimal.valueOf(11)
            )
        }
        record(
            "TreatmentPlanService", "patch", "недостаточно остатка", 1,
            "2 SELECT, UPDATE отсутствует", insufficientPatch
        )

        val (deleteMissing, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.delete(fixture.emptyUserId, UUID.randomUUID())
        }
        record(
            "TreatmentPlanService", "delete", "план отсутствует", 0,
            "404 после адресного SELECT", deleteMissing
        )

        val intakeFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(intakeFixture.ownerId, intakeFixture.drugId, BigDecimal.valueOf(5))
        }
        val (overPlan, _) = captureFailure(ResponseStatusException::class.java) {
            treatmentPlans.applyIntake(
                intakeFixture.ownerId,
                intakeFixture.drugId,
                BigDecimal.valueOf(6)
            )
        }
        record(
            "TreatmentPlanService", "applyIntake", "превышение плана", 1,
            "2 SELECT, DML отсутствует", overPlan
        )
    }

    @Test
    fun `IntakeService различает cache miss hit и conflict`() {
        intakeResultsCache.invalidateAll()
        val command = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(command.ownerId, command.drugId, BigDecimal.valueOf(5))
        }
        val intakeId = UUID.randomUUID()
        val miss = captureWrite {
            intakeService.record(command.ownerId, command.drugId, BigDecimal.ONE, intakeId)
        }
        record(
            "IntakeService", "record", "cache miss", 1,
            "приём применён и закеширован", miss
        )
        assertNoSql("IntakeService.record cache-hit") {
            intakeService.record(command.ownerId, command.drugId, BigDecimal.ONE, intakeId)
        }
        captureNoSqlFailure("IntakeService.record conflict") {
            intakeService.record(command.ownerId, command.drugId, BigDecimal.valueOf(2), intakeId)
        }

        val failedId = UUID.randomUUID()
        val (failed, _) = captureFailure(ResponseStatusException::class.java) {
            intakeService.record(command.ownerId, command.drugId, BigDecimal.TEN, failedId)
        }
        record(
            "IntakeService", "record", "неуспешный miss", 1,
            "результат не закеширован", failed
        )
        val retry = captureWrite {
            intakeService.record(command.ownerId, command.drugId, BigDecimal.ONE, failedId)
        }
        record(
            "IntakeService", "record", "retry после отказа", 1,
            "SQL выполнен повторно и успешно", retry
        )
    }

    @Test
    fun `IntakeService отражает удаление плана и Drug`() {
        intakeResultsCache.invalidateAll()
        val zeroPlanFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(
                zeroPlanFixture.ownerId,
                zeroPlanFixture.drugId,
                BigDecimal.ONE
            )
        }
        val zeroPlan = captureWrite {
            assertNull(
                intakeService.record(
                    zeroPlanFixture.ownerId,
                    zeroPlanFixture.drugId,
                    BigDecimal.ONE,
                    UUID.randomUUID()
                ).plan
            )
        }
        record(
            "IntakeService", "record", "план обнулён", 1,
            "Using удалён, Drug обновлён", zeroPlan
        )

        val exhaustFixture = fixture.createDrugFixture(0)
        tx.executeWithoutResult {
            treatmentPlans.create(
                exhaustFixture.ownerId,
                exhaustFixture.drugId,
                BigDecimal.TEN
            )
        }
        val exhausted = captureWrite {
            assertNull(
                intakeService.record(
                    exhaustFixture.ownerId,
                    exhaustFixture.drugId,
                    BigDecimal.TEN,
                    UUID.randomUUID()
                ).plan
            )
        }
        record(
            "IntakeService", "record", "Drug исчерпан", 1,
            "Drug и Using удалены каскадом", exhausted
        )
    }

    @Test
    fun `MedKit lifecycle покрывает создание приглашение и join`() {
        val userId = fixture.createUser("lifecycle-create")
        val created = captureWrite {
            medKitLifecycle.create(userId)
        }
        record(
            "MedKitLifecycleService", "create", "успех", 1,
            "аптечка и membership созданы", created
        )

        val command = fixture.createMedKitFixture(0, 0, additionalMember = false)
        lateinit var key: String
        val invitation = capture {
            key = medKitLifecycle.createInvitation(command.ownerId, command.medKitId)
        }
        record(
            "MedKitLifecycleService", "createInvitation", "успех", 1,
            "ключ записан только в cache", invitation
        )

        val joiningUser = fixture.createUser("joining")
        val joined = captureWrite {
            assertEquals(command.medKitId, medKitLifecycle.join(joiningUser, key))
        }
        record(
            "MedKitLifecycleService", "join", "успех", 1,
            "membership создан", joined
        )

        captureNoSqlFailure("MedKitLifecycleService.join expired-key") {
            medKitLifecycle.join(joiningUser, "missing-${UUID.randomUUID()}")
        }
    }

    @Test
    fun `MedKit lifecycle записывает SQL отказов`() {
        val (createMissing, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.create(UUID.randomUUID())
        }
        record(
            "MedKitLifecycleService", "create", "пользователь отсутствует", 0,
            "1 SELECT, INSERT отсутствует", createMissing
        )

        val command = fixture.createMedKitFixture(1, 0, additionalMember = false)
        val (invitationDenied, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.createInvitation(fixture.emptyUserId, command.medKitId)
        }
        record(
            "MedKitLifecycleService", "createInvitation", "нет доступа", 0,
            "404 после access SELECT", invitationDenied
        )

        val ownerKey = medKitLifecycle.createInvitation(command.ownerId, command.medKitId)
        val (alreadyMember, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.join(command.ownerId, ownerKey)
        }
        record(
            "MedKitLifecycleService", "join", "уже участник", 1,
            "409 после access SELECT", alreadyMember
        )

        val missingUserKey = medKitLifecycle.createInvitation(command.ownerId, command.medKitId)
        val (missingUser, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.join(UUID.randomUUID(), missingUserKey)
        }
        record(
            "MedKitLifecycleService", "join", "пользователь отсутствует", 0,
            "lookup аптечки и пользователя", missingUser
        )

        val (leaveDenied, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.leave(fixture.emptyUserId, command.medKitId)
        }
        record(
            "MedKitLifecycleService", "leave", "нет доступа", 0,
            "404 после lock SELECT", leaveDenied
        )

        val sameTarget = fixture.createMedKitFixture(1, 0, additionalMember = false)
        val (sameDelete, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.delete(
                sameTarget.ownerId,
                sameTarget.medKitId,
                sameTarget.medKitId
            )
        }
        record(
            "MedKitLifecycleService", "delete", "цель совпадает", 1,
            "аптечка и Drug заблокированы, DML отсутствует", sameDelete
        )

        val missingTarget = fixture.createMedKitFixture(1, 0, additionalMember = false)
        val (targetDenied, _) = captureFailure(ResponseStatusException::class.java) {
            medKitLifecycle.delete(
                missingTarget.ownerId,
                missingTarget.medKitId,
                UUID.randomUUID()
            )
        }
        record(
            "MedKitLifecycleService", "delete", "цель недоступна", 1,
            "3 SELECT, DML отсутствует", targetDenied
        )
    }

    @Test
    fun `чистые сервисы имеют подтверждённый zero SQL контракт`() {
        assertNoSql("SecurityService.generateKey") {
            assertTrue(securityService.generateKey(16).isNotBlank())
        }
        assertNoSql("SecurityService.hashPassword") {
            assertTrue(securityService.hashPassword("secret").isNotBlank())
        }
        val hash = securityService.hashPassword("secret")
        assertNoSql("SecurityService.check") {
            assertTrue(securityService.check("secret", hash))
        }
        assertNoSql("SecurityService.secretsMatch") {
            assertTrue(securityService.secretsMatch("same", "same"))
        }
        assertNoSql("SecurityService.generateToken") {
            assertTrue(
                securityService.generateToken(
                    User(fixture.ownerId, "{noop}query-plan"),
                    termInMinutes = 1
                ).isNotBlank()
            )
        }
        val address = "zero-sql-${UUID.randomUUID()}"
        assertNoSql("SecurityService.isLoginAllowed") {
            assertTrue(securityService.isLoginAllowed(address))
        }
        assertNoSql("SecurityService.isRegistrationAllowed") {
            assertTrue(securityService.isRegistrationAllowed(address))
        }
        assertNoSql("SecurityService.recordLoginAttempt") {
            securityService.recordLoginAttempt(address)
        }
        assertNoSql("SecurityService.recordRegisterAttempt") {
            securityService.recordRegisterAttempt(address)
        }
        assertNoSql("PlanReconciler.reconcile") {
            val result = PlanReconciler.reconcile(
                BigDecimal.TEN,
                listOf(BigDecimal.TEN, BigDecimal.TEN)
            )
            assertEquals(0, result.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.TEN))
        }
    }

    @Test
    fun `реестр SQL сценариев перечисляет каждый публичный метод DB поверхности`() {
        val classes = listOf(
            DrugService::class.java,
            UsingService::class.java,
            VidalDrugService::class.java,
            UserService::class.java,
            DrugCommandService::class.java,
            TreatmentPlanService::class.java,
            IntakeService::class.java,
            MedKitQueryService::class.java,
            MedKitLifecycleService::class.java,
            SecurityService::class.java,
            PlanReconciler::class.java
        )
        val actual = classes.flatMapTo(sortedSetOf()) { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
                .map { "${type.simpleName}.${it.name}" }
        }
        assertEquals(COVERED_PUBLIC_METHODS, actual)
    }

    @Test
    fun `summary аптечек использует индексы memberships и drugs`() {
        val executed = capture {
            val result = medKitQueries.listForUser(fixture.ownerId)
            assertTrue(result.isNotEmpty())
        }
        val plans = explainStatements(executed, forceIndexes = true)
        assertNoSeqScanOn("med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
        record(
            "MedKitQueryService", "listForUser", "summary", fixture.ownerMedKitCount,
            "${fixture.ownerMedKitCount} аптечек", executed
        )
    }

    private companion object {
        val COVERED_PUBLIC_METHODS = sortedSetOf(
            "DrugService.getAccessible",
            "UsingService.listForUser",
            "UsingService.getForUser",
            "VidalDrugService.fuzzySearch",
            "VidalDrugService.findByIdOrNull",
            "VidalDrugService.findById",
            "UserService.registerNewUser",
            "UserService.loadUserByUsername",
            "UserService.findById",
            "DrugCommandService.create",
            "DrugCommandService.patch",
            "DrugCommandService.consume",
            "DrugCommandService.move",
            "DrugCommandService.delete",
            "TreatmentPlanService.create",
            "TreatmentPlanService.patch",
            "TreatmentPlanService.delete",
            "TreatmentPlanService.applyIntake",
            "IntakeService.record",
            "MedKitQueryService.listForUser",
            "MedKitQueryService.getContent",
            "MedKitQueryService.getUserSnapshot",
            "MedKitLifecycleService.create",
            "MedKitLifecycleService.createInvitation",
            "MedKitLifecycleService.join",
            "MedKitLifecycleService.leave",
            "MedKitLifecycleService.delete",
            "SecurityService.generateKey",
            "SecurityService.check",
            "SecurityService.hashPassword",
            "SecurityService.secretsMatch",
            "SecurityService.generateToken",
            "SecurityService.isRegistrationAllowed",
            "SecurityService.isLoginAllowed",
            "SecurityService.recordLoginAttempt",
            "SecurityService.recordRegisterAttempt",
            "PlanReconciler.reconcile"
        )
    }
}
