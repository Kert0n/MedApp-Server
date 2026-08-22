package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Аутентификация не ходит через куки — и поэтому защита от CSRF выключена законно.
 *
 * CSRF — атака на **неявную** аутентификацию: браузер сам прикладывает куку к запросу, который
 * отправила чужая страница. Там, где токен прикладывает клиент заголовком `Authorization`,
 * подделывать нечего, и `csrf().disable()` не отключает ничего работающего.
 *
 * CodeQL этого различия не делает и даёт `java/spring-disabled-csrf-protection` на обе цепочки.
 * Находки закрыты как принятый риск — но «сейчас кук нет» это состояние, а не правило, и живёт
 * оно ровно до первого рефакторинга. Этот тест превращает состояние в правило: пока он зелёный,
 * обоснование закрытых находок остаётся верным.
 *
 * Если правило когда-нибудь снимут, вернуть надо и находки: отключённая защита при куковой
 * аутентификации — уже настоящая дыра, а не ложное срабатывание.
 */
class StatelessAuthTest {

    private val production: List<Path> =
        Files.walk(Path.of("src/main/kotlin")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `в проде нет куковой аутентификации`() {
        assertTrue(production.isNotEmpty(), "рабочих файлов не найдено — тест смотрит не туда")

        // По тексту, а не по типам: `CookieCsrfTokenRepository` и `ResponseCookie` попадают тем
        // же поиском, что и `Cookie`, а закомментированная кука — такое же начало пути.
        listOf("Cookie", "JSESSIONID").forEach { forbidden ->
            val offenders = production.flatMap { file ->
                file.readText().lines().withIndex()
                    .filter { (_, line) -> line.contains(forbidden) }
                    .map { (number, _) -> "${file.name}:${number + 1}" }
            }

            assertTrue(
                offenders.isEmpty(),
                "$forbidden в рабочем коде: аутентификация становится неявной, и выключенная " +
                    "защита от CSRF превращается в дыру\n${offenders.joinToString("\n")}"
            )
        }
    }

    @Test
    fun `каждая цепочка фильтров объявляет себя без состояния`() {
        val sources = production.map { it.readText() }
        val chains = sources.sumOf { COUNT_CHAINS.findAll(it).count() }
        val stateless = sources.sumOf { COUNT_STATELESS.findAll(it).count() }

        assertTrue(chains > 0, "цепочек фильтров не найдено — тест смотрит не туда")
        assertTrue(
            stateless >= chains,
            "цепочек $chains, а `STATELESS` объявлен $stateless раз: у той, что осталась без " +
                "объявления, Spring заведёт сессию — а вместе с сессией вернётся и кука"
        )

        val other = sources.flatMap { POLICY.findAll(it).map { match -> match.groupValues[1] } }
            .filter { it != "STATELESS" }

        assertTrue(
            other.isEmpty(),
            "политика сессии, отличная от `STATELESS`: ${other.joinToString()}"
        )
    }

    private companion object {
        val COUNT_CHAINS = Regex("""\): SecurityFilterChain""")
        val COUNT_STATELESS = Regex("""SessionCreationPolicy\.STATELESS""")
        val POLICY = Regex("""SessionCreationPolicy\.(\w+)""")
    }
}
