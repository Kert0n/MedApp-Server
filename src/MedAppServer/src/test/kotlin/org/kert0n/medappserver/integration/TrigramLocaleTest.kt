package org.kert0n.medappserver.integration

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * Триграммы обязаны работать с кириллицей.
 *
 * `pg_trgm` смотрит на `LC_CTYPE`, и в локали `C` кириллица буквой не считается: из русского
 * текста **не извлекается ни одной триграммы** — `similarity()` тождественно ноль, поиск по
 * опечатке молча перестаёт находить. Приложение при этом стартует и отвечает, так что по логам
 * этого не увидеть, а локаль легко потерять: другой образ, `--locale=C` в initdb, старый том.
 *
 * Тест проверяет **причину**, а не следствие: падает на свойстве базы, а не на «ничего не
 * нашлось», где искать пришлось бы в запросе.
 */
@PostgresIntegrationTest
@Transactional
class TrigramLocaleTest {


    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun scalar(sql: String): String = jdbc.queryForObject(sql, String::class.java)!!

    /**
     * Локаль текущей базы — атрибут базы, а не сервера.
     *
     * Через `pg_database`, а не `SHOW lc_ctype`: в Postgres 16 одноимённого параметра нет, и
     * `SHOW` падает с «unrecognized configuration parameter».
     */
    private fun currentCtype(): String =
        scalar("SELECT datctype FROM pg_database WHERE datname = current_database()")

    @Test
    fun `из кириллицы извлекаются триграммы`() {
        val trigrams = scalar("SELECT show_trgm('Аспирин')::text")
        assertTrue(
            trigrams.length > 2 && trigrams != "{}",
            "show_trgm('Аспирин') пуст — LC_CTYPE не даёт pg_trgm считать кириллицу буквами. " +
                "Текущая локаль: ${currentCtype()}"
        )
    }

    @Test
    fun `опечатка по-русски даёт ненулевое сходство`() {
        val similarity = scalar("SELECT similarity('аспирин', 'аспирн')").toDouble()
        assertTrue(
            similarity > 0.3,
            "similarity('аспирин','аспирн') = $similarity: ниже порога pg_trgm, " +
                "поиск по опечатке работать не будет"
        )
    }

    @Test
    fun `регистр кириллицы складывается`() {
        // Отдельно от триграмм: за это отвечает та же LC_CTYPE, но другой механизм, и
        // сломаться они могут порознь.
        assertTrue(
            scalar("SELECT lower('АСПИРИН')") == "аспирин",
            "lower() не приводит кириллицу к нижнему регистру: локаль ${currentCtype()}"
        )
    }

    @Test
    fun `локаль задана явно, а не унаследована`() {
        val ctype = currentCtype()
        assertTrue(
            !ctype.equals("C", ignoreCase = true) && !ctype.startsWith("POSIX"),
            "LC_CTYPE = $ctype: в этой локали pg_trgm не видит кириллицу"
        )
    }
}
