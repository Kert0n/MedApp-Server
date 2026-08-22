package org.kert0n.medappserver.queryplan

import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.RecordedSql
import org.kert0n.medappserver.db.store.CatalogueStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Нечёткий поиск доходит до своих индексов.
 *
 * Их два, и они про разное: GIN по `search_text` обслуживает поиск по словам с опечатками
 * через `<%`, GIN по `search_tsv` — точное совпадение слов. Ради них и заведена вся склейка;
 * если запрос до них не доходит, восемнадцать тысяч записей читаются целиком на каждый ввод
 * в поисковую строку.
 */
@PostgresIntegrationTest
class CatalogueQueryPlanTest {

    @Autowired private lateinit var fixture: LargeFixture
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var catalogue: CatalogueStore
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun populate() = fixture.ensurePopulated()

    /**
     * Слово выбрано редкое намеренно.
     *
     * В фикстуре все названия начинаются одинаково, и на частом слове планировщик прав, читая
     * всё подряд: индекс тут не помог бы. Проверять надо случай, ради которого индекс и стоит,
     * — когда подходящих записей мало.
     */
    @Test
    fun `поиск по словам доходит до триграммного индекса`() {
        val sql = sqlOf { catalogue.searchTemplates("12345", listOf("12345"), 10) }

        QueryPlan.withoutSeqScan(jdbc, sql).usesIndex("ix_parsed_drugs_search_text_trgm")
    }

    /**
     * Планировщик этот индекс **не выбирает**.
     *
     * На 18 000 записей полный проход по его оценке дешевле, и он прав по-своему: при пороге
     * 0.3 запрос выполняется за 0.35 мс, потому что `LIMIT 10` набирается на сто двадцатой
     * строке. Но это удача выборки, а не работа индекса: при пороге 0.6 тот же запрос читает
     * 12 338 строк за 37 мс, при 0.9 — все 18 000 за 55 мс, и растёт это линейно.
     *
     * Утверждать «полного прохода нет» значило бы требовать от планировщика решения, которое
     * он на этом объёме принимать не обязан. Поэтому здесь закрепляется факт: проход есть, и
     * когда он исчезнет — при росте таблицы или смене оценок, — тест об этом скажет.
     */
    @Test
    fun `на нынешнем объёме поиск идёт полным проходом`() {
        val sql = sqlOf { catalogue.searchTemplates("12345", listOf("12345"), 10) }

        val plan = QueryPlan.of(jdbc, sql)
        assertTrue(
            plan.describe().contains("Seq Scan parsed_drugs"),
            "проход исчез — планировщик стал выбирать индекс, и это хорошая новость:\n${plan.describe()}"
        )
    }

    /** Полнотекстовая ступень порядка тоже опирается на свой индекс. */
    @Test
    fun `полнотекстовый индекс применим к запросу`() {
        val sql = "SELECT id FROM parsed_drugs WHERE search_tsv @@ plainto_tsquery('simple', '12345')"

        QueryPlan.withoutSeqScan(jdbc, sql).usesIndex("ix_parsed_drugs_search_tsv")
    }

    private fun sqlOf(read: () -> Unit): String {
        val statements = TransactionTemplate(transactionManager).execute { RecordedSql.of(read) }!!
        assertTrue(statements.isNotEmpty(), "чтение не сделало ни одного запроса")
        return statements.maxBy { it.length }
    }
}
