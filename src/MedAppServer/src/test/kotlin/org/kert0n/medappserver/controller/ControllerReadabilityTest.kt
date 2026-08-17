package org.kert0n.medappserver.controller

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Контроллеры остаются читаемыми: тексты живут в `OPERATION_TEXTS`, а не в `@Operation`, и
 * `@ApiResponse` повторяется вместо обёртки `@ApiResponses`.
 *
 * Без этого теста следующий эндпойнт вернёт аннотации на треть исходника, и через несколько PR
 * файл снова станет нечитаемым.
 */
class ControllerReadabilityTest {

    private val controllers: List<Path> =
        Files.list(Path.of("src/main/kotlin/org/kert0n/medappserver/controller")).asSequence()
            .filter { it.name.endsWith("Controller.kt") }
            .toList()

    @Test
    fun `тексты операций не возвращаются в контроллеры`() {
        controllers.forEach { file ->
            val text = Files.readString(file)
            // Допустимо только описание требований безопасности: это поведение, а не текст.
            val forbidden = Regex("@Operation\\((?!security = )")
            assertTrue(
                !forbidden.containsMatchIn(text),
                "${file.name}: summary и description операций живут в OPERATION_TEXTS, " +
                    "а не в аннотациях. @Operation допустима только для security."
            )
        }
    }

    @Test
    fun `список ответов не заворачивается в ApiResponses`() {
        controllers.forEach { file ->
            val text = Files.readString(file)
            assertTrue(
                "@ApiResponses(" !in text,
                "${file.name}: @ApiResponse повторяемая, обёртка @ApiResponses(value = [ … ]) " +
                    "добавляет три строки лесов на каждый метод и ничего не даёт."
            )
        }
    }

    @Test
    fun `у каждой операции есть текст`() {
        // То же, что проверяет OperationTextCustomizer на старте, но с внятным падением.
        val declared = controllers.flatMap { file ->
            Regex("\\n    fun (\\w+)\\(").findAll(Files.readString(file)).map { it.groupValues[1] }
        }.toSet()

        val documented = org.kert0n.medappserver.services.OPERATION_TEXTS.keys
        val missing = declared - documented
        assertTrue(
            missing.isEmpty(),
            "нет текста для операций: $missing — добавьте их в OPERATION_TEXTS"
        )
    }
}
