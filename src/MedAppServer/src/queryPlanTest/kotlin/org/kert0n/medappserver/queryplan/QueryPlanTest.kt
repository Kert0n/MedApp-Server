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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import kotlin.test.assertTrue

/**
 * Проверяет **форму плана** горячих запросов на объёме, где она уже что-то значит.
 *
 * Почему не время: настоящей нагрузки у проекта нет, миллисекунды на синтетике зависят от
 * машины и от кеша страниц, и утверждать по ним нечего. А `Seq Scan` по таблице на
 * восемнадцать тысяч строк — факт, который от машины не зависит и в проде повторится.
 *
 * Почему не число операторов: этим занят `StatementCountTest` в обычном наборе. Здесь
 * вопрос другой — не «сколько запросов», а «каждый ли из них попадает в индекс».
 *
 * Набор запускается отдельно и в `check` не входит:
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
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var medKitDrugServices: MedKitDrugServices
    @Autowired private lateinit var vidalDrugService: VidalDrugService

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

    /**
     * Собирает планы всех SELECT сценария.
     *
     * DML пропускается: у вставок и удалений плана в интересном смысле нет, а проверять надо
     * именно доступ к данным.
     */
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

    /**
     * Проверяется не выбор планировщика, а способность запроса попасть в индекс.
     *
     * На восемнадцати тысячах строк каталог занимает пару сотен страниц, и `Seq Scan` по нему
     * планировщик выбирает законно — спорить с этим значит спорить с арифметикой. Дефектом
     * будет другое: запрос, который в индекс не попадает **ни при каких** настройках. Именно
     * это и ловится `enable_seqscan = off`, и от объёма данных оно не зависит.
     *
     * Так вскрылось, что `ILIKE CONCAT('%', :term, '%')` индексом не пользовался: `concat()`
     * объявлена stable, и шаблон для планировщика непрозрачен. Заменено на оператор `||`,
     * который для текста immutable.
     */
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

    /**
     * Число запросов не должно расти с числом аптечек.
     *
     * Счётчик, а не план: это вопрос «сколько раз», а не «каким способом». План здесь
     * бессилен — он показывает один запрос, а проблема в их количестве.
     */
    @Test
    fun `выдача пользователю не делает запрос на каждую аптечку`() {
        val executed = RecordingDataSource.capture {
            tx.executeWithoutResult {
                val medKits = medKitService.findAllByUser(fixture.ownerId)
                medKitDrugServices.drugsWithPlansByMedKit(medKits)
            }
        }
        println("\n=== GET /v1/user: аптечек ${fixture.ownerMedKitCount}, операторов ${executed.size} ===")

        assertTrue(
            executed.size <= 3,
            "у пользователя ${fixture.ownerMedKitCount} аптечек, а операторов ${executed.size}: " +
                "число запросов растёт с числом аптечек"
        )
    }

    /**
     * Как и в поиске: проверяется способность запроса попасть в индекс, а не выбор
     * планировщика. На синтетике в тридцать тысяч планов он предпочитает хеш-соединение с
     * последовательным чтением, и это законно — спорить с его арифметикой нечем.
     */
    @Test
    fun `выдача пользователю умеет читать препараты и планы по индексам`() {
        val plans = plansOf("GET /v1/user", forceIndexes = true) {
            val medKits = medKitService.findAllByUser(fixture.ownerId)
            medKitDrugServices.drugsWithPlansByMedKit(medKits)
        }

        val used = plans.flatMap { it.second.indexes }
        assertTrue(
            used.any { it.contains("med_kit_id") } && used.any { it.contains("usings") },
            "выборка обязана уметь идти по индексам препаратов и планов, а использованы: $used"
        )
    }

    /**
     * Число операторов не должно расти с числом планов.
     *
     * `Using.drug` объявлен EAGER, но производный запрос его не присоединяет: Hibernate
     * достаёт каждый препарат отдельным SELECT. На 167 планах это давало 169 операторов —
     * самый крупный N+1 в проекте, и в пользовательском эндпоинте.
     */
    @Test
    fun `список планов пользователя не делает запрос на каждый план`() {
        val executed = RecordingDataSource.capture {
            tx.executeWithoutResult { usingService.findAllByUser(fixture.ownerId) }
        }
        val plans = usingService.findAllByUser(fixture.ownerId).size
        println("\n=== GET /v1/using: планов $plans, операторов ${executed.size} ===")
        assertTrue(
            executed.size <= 3,
            "планов $plans, а операторов ${executed.size}: запрос на каждый план"
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
        val plans = plansOf("drug with plans") { drugService.findWithPlans(fixture.drugId) }
        assertNoSeqScanOn("user_drugs", plans)
        assertNoSeqScanOn("usings", plans)
    }
}
