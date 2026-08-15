package org.kert0n.medappserver.queryplan

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.persistence.repository.DrugAggregateRepository
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import kotlin.test.assertTrue

/** Проверяет формы планов production-запросов на крупном PostgreSQL fixture. */
@SpringBootTest
@Import(QueryPlanContainerConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanTest {

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var fixture: QueryPlanFixture
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var medKitDrugServices: MedKitDrugServices
    @Autowired private lateinit var vidalDrugService: VidalDrugService
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var drugAggregateRepository: DrugAggregateRepository
    @Autowired private lateinit var medKitQueryService: MedKitQueryService

    @Autowired private lateinit var container: PostgreSQLContainer

    private lateinit var tx: TransactionTemplate
    private lateinit var explainConnection: Connection

    @BeforeAll
    fun prepare() {
        tx = TransactionTemplate(transactionManager)
        fixture.seed()
        explainConnection = openExplainConnection(container)
    }

    @AfterAll
    fun tearDown() = explainConnection.close()

    /** Собирает планы уникальных SELECT сценария с фактическими параметрами. */
    private fun plansOf(
        name: String,
        forceIndexes: Boolean = false,
        scenario: () -> Unit
    ): List<Pair<String, QueryPlan>> {
        val executed = RecordingDataSource.capture { tx.executeWithoutResult { scenario() } }
        val plans = executed.filter { it.isSelect }
            .distinctBy { it.sql }
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

    private fun assertNoSeqScanOn(table: String, plans: List<Pair<String, QueryPlan>>) {
        val offenders = plans.filter { table in it.second.sequentiallyScanned }
        assertTrue(
            offenders.isEmpty(),
            "последовательное сканирование $table:\n" +
                offenders.joinToString("\n") { "  ${it.second}\n    ${it.first.take(200)}" }
        )
    }

    /** Forced-index план подтверждает индексируемость формы запроса каталога. */
    @Test
    fun `поиск по справочнику умеет пользоваться триграммными индексами`() {
        val plans = plansOf("поиск по каталогу", forceIndexes = true) {
            vidalDrugService.fuzzySearch(fixture.catalogueName, 10)
        }

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.startsWith("ix_parsed_drugs_") },
            "поиск обязан уметь задействовать индексы каталога, а использованы: $used"
        )
    }

    /** Чтение snapshot должно иметь индексируемые предикаты по аптечкам и планам. */
    @Test
    fun `выдача пользователю умеет читать препараты и планы по индексам`() {
        val plans = plansOf("GET /v1/user", forceIndexes = true) {
            val medKits = medKitService.findAllByUser(fixture.ownerId)
            medKits.forEach(medKitDrugServices::toMedKitDTO)
        }

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.contains("med_kit_id") } && used.any { it.contains("usings") },
            "выборка обязана уметь идти по индексам препаратов и планов, а использованы: $used"
        )
    }

    @Test
    fun `список планов пользователя идёт по индексу`() {
        val plans = plansOf("GET /v1/using") { usingService.findAllByUser(fixture.ownerId) }
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `выборка препарата под блокировку берёт индекс и запирает строку`() {
        val plans = plansOf("lock + load") {
            drugService.findByIdForUserForUpdate(fixture.drugId, fixture.ownerId)
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
        val plans = plansOf("drug with plans") {
            drugRepository.findByIdAndMedKitUsersIdWithUsings(fixture.drugId, fixture.ownerId)
        }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `aggregate repository блокирует Drug и затем читает планы по индексам`() {
        val plans = plansOf("aggregate lock and plans") {
            drugAggregateRepository.lockAggregate(fixture.ownerId, fixture.drugId)
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
        assertTrue(plans.any { "LockRows" in it.second.nodeTypes })
    }

    @Test
    fun `bulk lock препаратов имеет индексируемый предикат`() {
        val plans = plansOf("ordered medkit drug lock") {
            drugAggregateRepository.lockAllByMedKitIds(listOf(fixture.medKitId))
        }

        assertNoSeqScanOn("user_drugs", plans)
        assertTrue(plans.any { "LockRows" in it.second.nodeTypes })
    }

    @Test
    fun `содержимое аптечки читает доступ препараты и планы по индексам`() {
        val plans = plansOf("medkit content", forceIndexes = true) {
            medKitQueryService.getContent(fixture.ownerId, fixture.medKitId)
        }

        assertNoSeqScanOn("user_med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }

    @Test
    fun `snapshot читает все аптечки двумя индексируемыми формами запросов`() {
        val plans = plansOf("user snapshot", forceIndexes = true) {
            medKitQueryService.getUserSnapshot(fixture.ownerId)
        }

        assertNoSeqScanOn("user_med_kits", plans)
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }
}
