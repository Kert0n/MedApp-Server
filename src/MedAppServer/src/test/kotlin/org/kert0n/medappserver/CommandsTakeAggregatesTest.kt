package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Объект — доказательство доступа, идентификатор — то, что ещё надо проверить.
 *
 * Команда, принимающая идентификатор, соглашается, что вызывающий проверку прошёл, — а
 * проверил его никто. Читать полагается ровно один раз, наверху, и передавать вниз уже
 * доказанный агрегат.
 *
 * Идентификатор законен только в чтении: там он и проверяется предикатом членства, за чем
 * следит `QueryScopeTest`.
 */
class CommandsTakeAggregatesTest {

    private val sources: List<Path> =
        listOf("services/aggregate", "services/orchestrator").flatMap { layer ->
            Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver/$layer")).asSequence()
                .filter { it.name.endsWith(".kt") }
                .toList()
        }

    @Test
    fun `команда принимает агрегат, а не его идентификатор`() {
        assertTrue(sources.isNotEmpty(), "сервисов не найдено — тест смотрит не туда")

        val offenders = sources.flatMap { file ->
            // Команда — та, что открывает запись: у читающих стоит readOnly = true. Признак
            // взят по аннотации, а не по имени: она и так обязана быть верной.
            val commands = Regex(
                "@Transactional\\(propagation = MANDATORY\\)\\s*\\n\\s*fun (\\w+)\\(([^)]*)\\)",
                RegexOption.MULTILINE
            ).findAll(Files.readString(file)).toList()

            // Форма с агрегатом — основная; та, что берёт идентификатор, лишь добавляет к ней
            // честное чтение. Одна такая без пары означает команду, которой доступ никто не
            // проверял.
            val withAggregate = commands
                .filterNot { FORBIDDEN.containsMatchIn(it.groupValues[2]) }
                .map { it.groupValues[1] }
                .toSet()

            commands
                .filter { FORBIDDEN.containsMatchIn(it.groupValues[2]) }
                .filterNot { it.groupValues[1] in withAggregate }
                .map { "${file.name}.${it.groupValues[1]}" }
        }

        assertEquals(
            emptyList(), offenders,
            "команда взяла идентификатор вместо агрегата: значит доступ к нему никто не проверял"
        )
    }

    private companion object {
        // `userId` намеренно не здесь: это не «кого проверить», а данные — чья бронь, кто выходит.
        val FORBIDDEN = Regex("\\b(drugId|medKitId|reservationId|targetMedKitId|sourceMedKitId)\\s*:")
    }
}
