package org.kert0n.medappserver

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.allSources

/**
 * Аутентификация не ходит через куки, и поэтому защита от CSRF выключена законно.
 *
 * CSRF — атака на **неявную** аутентификацию: браузер сам прикладывает куку к запросу, который
 * отправила чужая страница. Там, где токен прикладывает клиент заголовком `Authorization`,
 * подделывать нечего, и `csrf().disable()` не отключает ничего работающего.
 *
 * CodeQL этого различия не делает и даёт `java/spring-disabled-csrf-protection` на обе цепочки.
 * Основание закрыть эти находки — не «сейчас кук нет»: это состояние, и живёт оно до первого
 * рефакторинга. Основание — вот это правило. Пока тест зелёный, обоснование верно; если правило
 * снимут, находки надо открывать заново, потому что отключённая защита при неявной
 * аутентификации — уже настоящая дыра.
 */
class StatelessAuthTest {

    private val production: List<Source> = allSources().map { Source(it, it.readText()) }

    @Test
    fun `в проде нет куковой и сессионной аутентификации`() {
        assertTrue(production.isNotEmpty(), "рабочих файлов не найдено — тест смотрит не туда")

        // По тексту, а не по типам: слово в комментарии считается нарушением — «здесь могла бы
        // быть кука», оставленное в коде, ровно тот способ, которым запрет размывается.
        //
        // `HttpSession` ловит и `HttpSessionSecurityContextRepository`: он восстанавливает
        // аутентификацию из сессии, не упоминая ни куки, ни `JSESSIONID`, — а сессия и есть та
        // неявная аутентификация, ради отсутствия которой всё это правило.
        listOf("Cookie", "JSESSIONID", "HttpSession", "getSession").forEach { forbidden ->
            val offenders = production.flatMap { it.linesWith(forbidden) }

            assertTrue(
                offenders.isEmpty(),
                "$forbidden в рабочем коде: аутентификация становится неявной, и выключенная " +
                    "защита от CSRF превращается в дыру\n${offenders.joinToString("\n")}"
            )
        }
    }

    /**
     * Каждая цепочка объявляет `STATELESS` **сама**.
     *
     * Счёта по файлу не хватает: две отметки в одной цепочке покрыли бы её отсутствие в другой,
     * а Spring завёл бы сессию именно там, где отметки нет. Поэтому файл режется по цепочкам, и
     * каждая проверяется отдельно.
     *
     * Комментарии из тела вырезаются: упоминание политики рядом с цепочкой — не объявление.
     */
    @Test
    fun `каждая цепочка фильтров объявляет себя без состояния`() {
        val chains = production.flatMap { it.chains() }

        assertTrue(chains.isNotEmpty(), "цепочек фильтров не найдено — тест смотрит не туда")

        val silent = chains.filterNot { it.body.contains("SessionCreationPolicy.STATELESS") }
        assertTrue(
            silent.isEmpty(),
            "цепочка не объявила `STATELESS`: Spring заведёт для неё сессию, а вместе с сессией " +
                "вернётся кука\n${silent.joinToString("\n") { it.where }}"
        )

        val other = chains.flatMap { chain ->
            POLICY.findAll(chain.body)
                .map { it.groupValues[1] }
                .filter { it != "STATELESS" }
                .map { "${chain.where}: $it" }
        }
        assertTrue(other.isEmpty(), "политика сессии, отличная от `STATELESS`:\n${other.joinToString("\n")}")
    }

    /** Рабочий файл вместе с текстом: читается один раз, дальше только разбирается. */
    private class Source(val path: Path, val text: String) {

        fun linesWith(forbidden: String): List<String> =
            text.lines().withIndex()
                .filter { (_, line) -> line.contains(forbidden) }
                .map { (number, _) -> "${path.name}:${number + 1}" }

        /**
         * Тела цепочек: от объявления возвращаемого типа до следующего такого объявления.
         *
         * Разбор текстовый, как и все правила части 2: они про то, что **написано**. Цена —
         * цепочка, собранная не в функции, останется незамеченной; на этот случай есть первый
         * тест, который запрещает сессионное API вообще.
         */
        fun chains(): List<Chain> {
            val marks = CHAIN.findAll(text).map { it.range.first }.toList()
            return marks.mapIndexed { index, start ->
                val end = marks.getOrNull(index + 1) ?: text.length
                val line = text.take(start).count { it == '\n' } + 1
                Chain("${path.name}:$line", text.substring(start, end).withoutComments())
            }
        }

        private fun String.withoutComments(): String =
            replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
    }

    private class Chain(val where: String, val body: String)

    private companion object {
        val CHAIN = Regex("""\): SecurityFilterChain""")
        val POLICY = Regex("""SessionCreationPolicy\.(\w+)""")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
