package org.kert0n.medappserver.queryplan

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** Фикстур обязан быть того объёма, ради которого он заведён, и со свежей статистикой. */
@PostgresIntegrationTest
class LargeFixtureTest {

    @Autowired private lateinit var fixture: LargeFixture
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun populate() = fixture.ensurePopulated()

    @Test
    fun `строк столько, сколько заявлено`() {
        assertEquals(10_000, fixture.count("user_drugs"), "упаковок")
        assertEquals(30_000, fixture.count("reservations"), "броней: по одной на участника каждой аптечки")
        assertEquals(18_000, fixture.count("parsed_drugs"), "записей справочника")
    }

    /**
     * Без свежей статистики планировщик выбирает вслепую, и падения набора были бы случайными.
     */
    @Test
    fun `статистика собрана`() {
        val analysed = jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_user_tables WHERE relname = 'user_drugs' AND last_analyze IS NOT NULL",
            Long::class.java
        )
        assertTrue((analysed ?: 0) > 0, "ANALYZE по user_drugs не выполнялся")
    }

    /** Сумма броней в фикстуре обязана сходиться со строками: иначе он неверен как состояние. */
    @Test
    fun `сумма броней сходится со строками`() {
        val diverged = jdbc.queryForObject(
            """
            SELECT count(*) FROM user_drugs d
            WHERE d.reservations_total <>
                COALESCE((SELECT sum(amount) FROM reservations r WHERE r.drug_id = d.id), 0)
            """.trimIndent(),
            Long::class.java
        )
        assertEquals(0, diverged, "у скольких упаковок хранимая сумма разошлась со строками")
    }
}
