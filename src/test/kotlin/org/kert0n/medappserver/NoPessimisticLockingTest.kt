package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Конкурентность держится на версиях, и ничем другим.
 *
 * `FOR UPDATE` и пессимистичные блокировки решают ту же задачу иначе: они удерживают строку до
 * конца транзакции. Смешивать два подхода хуже, чем выбрать любой: под нагрузкой они дают
 * взаимные блокировки, а по коду становится непонятно, что именно защищает запись.
 *
 * `JdbcTemplate` в проде запрещён отдельно: запрос перестаёт быть выражением, а типы — своими.
 * Единственное законное обращение к драйверу живёт в тесте, который нарочно ходит мимо
 * приложения.
 */
class NoPessimisticLockingTest {

    private val production: List<Path> =
        Files.walk(Path.of("src/main/kotlin")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `в проде нет прямого обращения к драйверу`() {
        assertTrue(production.isNotEmpty(), "рабочих файлов не найдено — тест смотрит не туда")

        assertNothingMentions(
            "JdbcTemplate",
            "хранилище пошло в обход Exposed: запрос перестал быть выражением, а типы — своими"
        )
    }

    @Test
    fun `в проде нет пессимистичных блокировок`() {
        listOf("FOR UPDATE", "forUpdate", "LockMode", "PESSIMISTIC").forEach { forbidden ->
            assertNothingMentions(
                forbidden,
                "конкурентность держится на версиях; блокировка вернёт то, от чего уходили"
            )
        }
    }

    /**
     * Ищется по тексту, а не по типам: слово в комментарии тоже считается нарушением.
     *
     * Написать «здесь мог бы быть FOR UPDATE» и оставить — ровно тот способ, которым запрет
     * размывается; сообщение называет файл и строку, так что разобраться легко.
     */
    private fun assertNothingMentions(forbidden: String, why: String) {
        val offenders = production.flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) -> line.contains(forbidden) }
                .map { (number, _) -> "${file.name}:${number + 1}" }
        }

        assertTrue(offenders.isEmpty(), "$forbidden в рабочем коде: $why\n${offenders.joinToString("\n")}")
    }
}
