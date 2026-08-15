package org.kert0n.medappserver.queryplan

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.persistence.repository.DrugAggregateRepository
import org.kert0n.medappserver.application.query.CatalogueQueryService
import org.kert0n.medappserver.application.query.DrugQueryService
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
import org.kert0n.medappserver.application.model.IntakePayload
import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.model.PatchDrugCommand
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.domain.drug.TreatmentPlan
import org.kert0n.medappserver.domain.drug.TreatmentPlanBook
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.db.model.User
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.math.BigDecimal
import java.util.UUID

/** Проверяет формы планов production-запросов на крупном PostgreSQL fixture. */
@SpringBootTest
@Import(QueryPlanContainerConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanTest {

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var fixture: QueryPlanFixture
    @Autowired private lateinit var drugAggregateRepository: DrugAggregateRepository
    @Autowired private lateinit var catalogueQueryService: CatalogueQueryService
    @Autowired private lateinit var drugQueryService: DrugQueryService
    @Autowired private lateinit var treatmentPlanQueryService: TreatmentPlanQueryService
    @Autowired private lateinit var medKitQueryService: MedKitQueryService
    @Autowired private lateinit var intakeOrchestrator: IntakeOrchestrator
    @Autowired private lateinit var drugOrchestrator: DrugOrchestrator
    @Autowired private lateinit var treatmentPlanOrchestrator: TreatmentPlanOrchestrator
    @Autowired private lateinit var medKitOrchestrator: MedKitOrchestrator
    @Autowired private lateinit var securityService: SecurityService

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

    /** Собирает планы уникальных SELECT сценария с фактическими параметрами. */
    private fun plansOf(
        name: String,
        owner: String,
        method: String,
        forceIndexes: Boolean = false,
        scenario: () -> Unit
    ): List<Pair<String, QueryPlan>> {
        val executed = RecordingDataSource.capture { tx.executeWithoutResult { scenario() } }
        executed.queryShape()
        val naturalPlans = executed
            .distinctBy { it.sql }
            .mapNotNull { statement ->
                explain(explainConnection, objectMapper, statement, false)
                    ?.let { statement.sql to it }
            }
        val forcedPlans = if (forceIndexes) executed.filter { it.isSelect }
            .distinctBy { it.fingerprint }
            .mapNotNull { statement ->
                explain(explainConnection, objectMapper, statement, true)?.let { statement.sql to it }
            } else emptyList()
        val plans = if (forceIndexes) forcedPlans else naturalPlans
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
                owner = owner,
                method = method,
                branch = name,
                size = null,
                result = "${executed.size} SQL",
                statements = executed,
                naturalPlans = naturalPlans.map { it.second },
                forcedPlans = forcedPlans.map { it.second }
            )
        )
        return plans
    }

    private fun assertNoSeqScanOn(table: String, plans: List<Pair<String, QueryPlan>>) {
        val offenders = plans.filter { table in it.second.sequentiallyScanned }
        assertTrue(
            offenders.isEmpty(),
            "последовательное сканирование $table:\n" +
                offenders.joinToString("\n") { "  ${it.second}\n    ${it.first.take(200)}" }
        )
    }

    private fun recordZeroSql(owner: String, method: String, branch: String, scenario: () -> Unit) {
        val statements = RecordingDataSource.capture(scenario)
        assertTrue(statements.isEmpty(), "$owner.$method executed SQL:\n${statements.diagnostic()}")
        report.record(QueryMeasurement(owner, method, branch, null, "0 SQL confirmed", emptyList(), complexity = "0 SQL"))
    }

    private fun captureAndRecord(
        owner: String,
        method: String,
        branch: String = "success",
        size: Int? = null,
        scenario: () -> Unit
    ): List<ExecutedStatement> {
        val statements = RecordingDataSource.capture(scenario)
        statements.queryShape()
        val natural = statements.distinctBy(ExecutedStatement::fingerprint)
            .mapNotNull { explain(explainConnection, objectMapper, it, false) }
        val forced = statements.filter(ExecutedStatement::isSelect)
            .distinctBy(ExecutedStatement::fingerprint)
            .mapNotNull { explain(explainConnection, objectMapper, it, true) }
        report.record(
            QueryMeasurement(owner, method, branch, size, "completed", statements, natural, forced)
        )
        return statements
    }

    @Test
    fun `application command surface records every successful SQL shape`() {
        val create = fixture.createDrugCommandFixture(0)
        captureAndRecord("DrugOrchestrator", "create") {
            drugOrchestrator.create(
                create.ownerId,
                CreateDrugCommand(create.medKitId, "Created", BigDecimal("10"), "таб")
            )
        }
        val patch = fixture.createDrugCommandFixture(0)
        captureAndRecord("DrugOrchestrator", "patch") {
            drugOrchestrator.patch(patch.ownerId, patch.drugId, PatchDrugCommand(name = "Patched"))
        }
        val consume = fixture.createDrugCommandFixture(0)
        captureAndRecord("DrugOrchestrator", "consume", "remaining drug") {
            drugOrchestrator.consume(consume.ownerId, consume.drugId, BigDecimal.ONE)
        }
        val move = fixture.createDrugCommandFixture(3)
        captureAndRecord("DrugOrchestrator", "move", "bulk plan filtering", 3) {
            drugOrchestrator.move(move.ownerId, move.drugId, move.targetMedKitId)
        }
        val delete = fixture.createDrugCommandFixture(3)
        captureAndRecord("DrugOrchestrator", "delete", "cascade", 3) {
            drugOrchestrator.delete(delete.ownerId, delete.drugId)
        }

        val createPlan = fixture.createDrugCommandFixture(0)
        captureAndRecord("TreatmentPlanOrchestrator", "create") {
            treatmentPlanOrchestrator.create(
                createPlan.ownerId,
                CreateTreatmentPlanCommand(createPlan.drugId, BigDecimal("5"))
            )
        }
        val patchPlan = fixture.createIntakeFixture()
        captureAndRecord("TreatmentPlanOrchestrator", "patch") {
            treatmentPlanOrchestrator.patch(patchPlan.ownerId, patchPlan.drugId, BigDecimal("4"))
        }
        val deletePlan = fixture.createIntakeFixture()
        captureAndRecord("TreatmentPlanOrchestrator", "delete") {
            treatmentPlanOrchestrator.delete(deletePlan.ownerId, deletePlan.drugId)
        }

        val userId = fixture.createUserFixture()
        captureAndRecord("MedKitOrchestrator", "create") { medKitOrchestrator.create(userId) }
        val invitationKit = fixture.createMedKitCommandFixture(0, 0, false)
        lateinit var invitation: String
        captureAndRecord("MedKitOrchestrator", "createInvitation") {
            invitation = medKitOrchestrator.createInvitation(
                invitationKit.ownerId,
                invitationKit.medKitId
            ).key
        }
        val joiningUser = fixture.createUserFixture()
        captureAndRecord("MedKitOrchestrator", "join") {
            medKitOrchestrator.join(joiningUser, invitation)
        }
        val leave = fixture.createMedKitCommandFixture(3, 3, true)
        captureAndRecord("MedKitOrchestrator", "leave", "non-last member", 3) {
            medKitOrchestrator.leave(leave.ownerId, leave.medKitId)
        }
        val remove = fixture.createMedKitCommandFixture(3, 3, false)
        captureAndRecord("MedKitOrchestrator", "delete", "cascade", 3) {
            medKitOrchestrator.delete(remove.ownerId, remove.medKitId)
        }
        val transfer = fixture.createMedKitCommandFixture(3, 3, false)
        val target = fixture.createTargetMedKit(transfer.ownerId)
        captureAndRecord("MedKitOrchestrator", "delete", "bulk transfer", 3) {
            medKitOrchestrator.delete(transfer.ownerId, transfer.medKitId, target)
        }
    }

    @Test
    fun `query surface records address and collection branches`() {
        val planUser = fixture.planUsers.getValue(1)
        val planDrug = fixture.planDrugs.getValue(1)
        captureAndRecord("TreatmentPlanQueryService", "getForUser") {
            treatmentPlanQueryService.getForUser(planUser, planDrug)
        }
        captureAndRecord("MedKitQueryService", "listForUser") {
            medKitQueryService.listForUser(fixture.ownerId)
        }
        captureAndRecord("CatalogueQueryService", "get") {
            catalogueQueryService.get(fixture.catalogueId)
        }
    }

    @Test
    fun `security and reconciliation have explicit zero SQL contracts`() {
        recordZeroSql("SecurityService", "generateKey", "success") {
            securityService.generateKey(32)
        }
        val hash = securityService.hashPassword("secret")
        recordZeroSql("SecurityService", "hashAndCheck", "success") {
            assertTrue(securityService.check("secret", hash))
            securityService.hashToken("token")
            securityService.secretsMatch("same", "same")
        }
        recordZeroSql("SecurityService", "rateLimits", "cache operations") {
            securityService.validateRequest("192.0.2.1")
            securityService.registerIncrease("192.0.2.1")
            securityService.isLoginAllowed("192.0.2.1")
            securityService.recordLoginAttempt("192.0.2.1")
        }
        recordZeroSql("SecurityService", "generateToken", "success") {
            securityService.generateToken(User(id = UUID.randomUUID(), hashedKey = hash))
        }
        recordZeroSql("TreatmentPlanBook", "reconcileTo", "pure domain") {
            val drugId = UUID.randomUUID()
            val book = TreatmentPlanBook(
                drugId,
                listOf(TreatmentPlan.create(UUID.randomUUID(), drugId, BigDecimal("10")))
            )
            book.reconcileTo(BigDecimal("5"))
        }
    }

    @Test
    fun `intake cache hit and conflict execute no SQL`() {
        val command = fixture.createIntakeFixture()
        val intakeId = UUID.randomUUID()
        val payload = IntakePayload(command.drugId, BigDecimal("2"))
        val miss = RecordingDataSource.capture {
            intakeOrchestrator.record(command.ownerId, intakeId, payload)
        }
        miss.queryShape()
        report.record(QueryMeasurement("IntakeOrchestrator", "record", "cache miss", 1, "committed", miss))

        recordZeroSql("IntakeOrchestrator", "record", "identical cache hit") {
            intakeOrchestrator.record(command.ownerId, intakeId, payload)
        }
        recordZeroSql("IntakeOrchestrator", "record", "conflicting cache hit") {
            assertFailsWith<org.kert0n.medappserver.domain.error.IntakeConflict> {
                intakeOrchestrator.record(
                    command.ownerId,
                    intakeId,
                    payload.copy(quantity = BigDecimal("1"))
                )
            }
        }

        val retryCommand = fixture.createIntakeFixture(planned = 2)
        val retryId = UUID.randomUUID()
        val failed = RecordingDataSource.capture {
            assertFailsWith<org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock> {
                intakeOrchestrator.record(
                    retryCommand.ownerId,
                    retryId,
                    IntakePayload(retryCommand.drugId, BigDecimal("5"))
                )
            }
        }
        report.record(QueryMeasurement("IntakeOrchestrator", "record", "failed miss rollback", 1, "rolled back", failed))
        val retry = RecordingDataSource.capture {
            intakeOrchestrator.record(
                retryCommand.ownerId,
                retryId,
                IntakePayload(retryCommand.drugId, BigDecimal("2"))
            )
        }
        retry.queryShape()
        report.record(QueryMeasurement("IntakeOrchestrator", "record", "retry after failure", 1, "committed", retry))
    }

    /** Forced-index план подтверждает индексируемость формы запроса каталога. */
    @Test
    fun `поиск по справочнику умеет пользоваться триграммными индексами`() {
        val plans = plansOf("поиск по каталогу", "CatalogueQueryService", "search", forceIndexes = true) {
            catalogueQueryService.search(fixture.catalogueName, 10)
        }

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.startsWith("ix_parsed_drugs_") },
            "поиск обязан уметь задействовать индексы каталога, а использованы: $used"
        )
    }

    @Test
    fun `список планов пользователя идёт по индексу`() {
        val plans = plansOf("список планов", "TreatmentPlanQueryService", "listForUser") {
            treatmentPlanQueryService.listForUser(fixture.ownerId)
        }
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `доступный Drug читается одной индексируемой SQL формой`() {
        val ids = fixture.drugsByPlanCount.getValue(100)
        val plans = plansOf("доступный Drug", "DrugQueryService", "getAccessible", forceIndexes = true) {
            drugQueryService.getAccessible(ids.first, ids.second)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("user_med_kits", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `выборка препарата под блокировку берёт индекс и запирает строку`() {
        val plans = plansOf("lock + load", "DrugAggregateRepository", "lockAccessible") {
            drugAggregateRepository.lockAccessible(fixture.ownerId, fixture.drugId)
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertTrue(
            plans.any { "LockRows" in it.second.nodeTypes },
            "в плане обязан быть LockRows: на этой блокировке держится защита от гонки. " +
                "Планы: ${plans.map { it.second }}"
        )
    }

    @Test
    fun `aggregate repository блокирует Drug и затем читает планы по индексам`() {
        val plans = plansOf("aggregate lock and plans", "DrugAggregateRepository", "lockAggregate") {
            drugAggregateRepository.lockAggregate(fixture.ownerId, fixture.drugId)
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertTrue(plans.any { "LockRows" in it.second.nodeTypes })
    }

    @Test
    fun `bulk lock препаратов имеет индексируемый предикат`() {
        val plans = plansOf("ordered medkit drug lock", "DrugAggregateRepository", "lockAllByMedKitIds") {
            drugAggregateRepository.lockAllByMedKitIds(listOf(fixture.medKitId))
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertTrue(plans.any { "LockRows" in it.second.nodeTypes })
    }

    @Test
    fun `содержимое аптечки читает доступ препараты и планы по индексам`() {
        val plans = plansOf("medkit content", "MedKitQueryService", "getContent", forceIndexes = true) {
            medKitQueryService.getContent(fixture.ownerId, fixture.medKitId)
        }

        assertNoSeqScanOn("user_med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `snapshot читает все аптечки двумя индексируемыми формами запросов`() {
        val plans = plansOf("user snapshot", "MedKitQueryService", "getUserSnapshot", forceIndexes = true) {
            medKitQueryService.getUserSnapshot(fixture.ownerId)
        }

        assertNoSeqScanOn("user_med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }
}
