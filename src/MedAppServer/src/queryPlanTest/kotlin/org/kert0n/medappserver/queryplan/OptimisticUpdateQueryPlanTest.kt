package org.kert0n.medappserver.queryplan

import kotlin.test.assertTrue
import kotlin.uuid.toKotlinUuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.RecordedSql
import org.kert0n.medappserver.db.store.DrugStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

/**
 * Оптимистичная запись находит строку по ключу, а не ищет её проходом.
 *
 * Предикат версии стоит в условии каждого `UPDATE`, и если он превратит запись в полный
 * проход, цена конкурентности станет непомерной: она платится на каждой команде, а не на
 * редком чтении.
 */
@PostgresIntegrationTest
class OptimisticUpdateQueryPlanTest {

    @Autowired private lateinit var fixture: LargeFixture
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var drugs: DrugStore
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun populate() = fixture.ensurePopulated()

    @Test
    fun `запись упаковки идёт по первичному ключу`() {
        val sql = updateSql()

        QueryPlan.of(jdbc, sql).scansNothingIn("user_drugs")
    }

    @Test
    fun `предикат версии стоит в самом операторе`() {
        val sql = updateSql()

        assertTrue(
            sql.substringAfter(" WHERE ", "").contains("version"),
            "проверка версии обязана стоять в условии самой записи, иначе между проверкой и\n" +
                "записью можно втиснуться:\n$sql"
        )
    }

    /**
     * Запись выполняется по-настоящему и откатывается: `EXPLAIN` нужен готовый оператор, а
     * получить его можно только от самого хранилища.
     */
    private fun updateSql(): String {
        val template = TransactionTemplate(transactionManager)
        template.isReadOnly = false
        return template.execute(
            TransactionCallback { status ->
                val drugId = jdbc.queryForObject("SELECT id FROM user_drugs LIMIT 1", java.util.UUID::class.java)!!
                    .toKotlinUuid()
                val userId = jdbc.queryForObject("SELECT user_id FROM user_med_kits LIMIT 1", java.util.UUID::class.java)!!
                    .toKotlinUuid()
                val drug = drugs.find(drugId, userId) ?: error("Упаковка $drugId не найдена")

                // Версия предъявляется явно с #100: запись без неё не собирается. Предъявляем
                // ту, что прочитали, — предикат от этого не слабеет, а замеряем мы его форму.
                val statements = RecordedSql.of { drugs.save(drug, stated = drug.version) }
                status.setRollbackOnly()
                statements.single { it.startsWith("UPDATE") }
            }
        )!!
    }
}
