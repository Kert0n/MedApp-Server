package org.kert0n.medappserver.integration

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertTrue

/**
 * Триграммы обязаны работать с кириллицей.
 *
 * `pg_trgm` определяет «буквенность» символа через `iswalpha` по `LC_CTYPE`. В локали `C`
 * кириллица буквой не считается, поэтому из русского текста **не извлекается ни одной
 * триграммы**: `similarity()` тождественно ноль, `show_trgm()` пуст, поиск по опечатке
 * молча перестаёт находить. Приложение при этом стартует и отвечает — ломается только
 * качество выдачи, и заметить это по логам нельзя.
 *
 * До этого набора локаль нигде не задавалась и наследовалась от образа. Работало по
 * везению: у `postgres:*-trixie` в переменной окружения стоит `en_US.utf8`. Любой другой
 * образ, `--locale=C` в аргументах initdb или том, созданный когда-то иначе, — и поиск
 * тихо деградирует.
 *
 * Тест проверяет **причину**, а не следствие: падает на свойстве базы, а не на «поиск
 * ничего не нашёл», где искать пришлось бы в запросе.
 */
@PostgresIntegrationTest
@Transactional
class TrigramLocaleTest {

    @Autowired private lateinit var entityManager: EntityManager

    private fun scalar(sql: String): String =
        entityManager.createNativeQuery(sql).singleResult.toString()

    /**
     * Локаль текущей базы.
     *
     * Через `pg_database`, а не `SHOW lc_ctype`: в Postgres 16 одноимённый параметр убрали,
     * и `SHOW` на нём падает с «unrecognized configuration parameter». Локаль с тех пор —
     * атрибут базы, а не сервера.
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
