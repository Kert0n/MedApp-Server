package org.kert0n.medappserver.queryplan

import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.RecordedSql
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Чтения упаковок и броней доходят до индексов.
 *
 * Измеряется тот SQL, который порождает само хранилище: переписать запрос руками было бы
 * проще, но тогда проверялся бы переписанный, а расхождение между ними — ровно то, что набор
 * и должен ловить.
 */
@PostgresIntegrationTest
class DrugQueryPlanTest {

    @Autowired private lateinit var fixture: LargeFixture
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var drugs: DrugStore
    @Autowired private lateinit var reservations: ReservationStore
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var someUser: Uuid
    private lateinit var someMedKit: Uuid

    @BeforeEach
    fun populate() {
        fixture.ensurePopulated()
        // Драйвер отдаёт джавовый тип: переводим на границе, как и всюду, где говорим с ним
        // напрямую.
        val membership = jdbc.queryForMap("SELECT med_kit_id, user_id FROM user_med_kits LIMIT 1")
        someMedKit = (membership["med_kit_id"] as java.util.UUID).toKotlinUuid()
        someUser = (membership["user_id"] as java.util.UUID).toKotlinUuid()
    }

    @Test
    fun `содержимое аптечки идёт по индексу аптечки`() {
        val sql = sqlOf { drugs.findAllInMedKit(someMedKit, someUser) }

        QueryPlan.withoutSeqScan(jdbc, sql).usesIndex("ix_user_drugs_med_kit_id")
    }

    @Test
    fun `свои брони идут по индексу пользователя`() {
        val sql = sqlOf { reservations.findAllOfUser(someUser) }

        QueryPlan.withoutSeqScan(jdbc, sql).usesIndex("ix_reservations_user_id")
    }

    /**
     * Скоуп по членству не должен превращаться в полный проход по членству: на трёхстах
     * аптечках это незаметно, на трёх тысячах — уже нет.
     */
    @Test
    fun `скоуп по членству идёт по ключу членства`() {
        val sql = sqlOf { drugs.findAllInMedKit(someMedKit, someUser) }

        QueryPlan.withoutSeqScan(jdbc, sql).scansNothingIn("user_med_kits")
    }

    /** Чтение одной пачки обязано идти по первичному ключу, а не искать её проходом. */
    @Test
    fun `чтение упаковки по идентификатору идёт по первичному ключу`() {
        val drugId = (jdbc.queryForObject("SELECT id FROM user_drugs LIMIT 1", java.util.UUID::class.java)!!)
            .toKotlinUuid()
        val sql = sqlOf { drugs.find(drugId, someUser) }

        QueryPlan.of(jdbc, sql).scansNothingIn("user_drugs")
    }

    /** Записанный SQL — тот единственный, что сделало чтение. */
    private fun sqlOf(read: () -> Unit): String {
        val statements = TransactionTemplate(transactionManager).execute { RecordedSql.of(read) }!!
        assertTrue(statements.isNotEmpty(), "чтение не сделало ни одного запроса")
        return statements.maxBy { it.length }
    }
}
