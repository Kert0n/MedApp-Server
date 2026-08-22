package org.kert0n.medappserver.controller

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Аннотации контроллера остаются плоскими и полными.
 *
 * `@ApiResponse` повторяется вместо обёртки `@ApiResponses`, а `@Operation` у каждой операции
 * несёт и текст, и требование аутентификации. Без этих правил следующий эндпойнт добавит три
 * строки лесов на метод, уедет в контракт безымянным или молча окажется документирован как
 * открытый.
 */
class ControllerReadabilityTest {

    private val controllers: List<Path> =
        Files.list(Path.of("src/main/kotlin/org/kert0n/medappserver/controller")).asSequence()
            .filter { it.name.endsWith("Controller.kt") }
            .toList()

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

    /**
     * Проверяется по исходнику, а не рефлексией.
     *
     * Пустой `security = []` — это ещё и значение по умолчанию у `@Operation`, поэтому в
     * class-файле «объявили, что требований нет» неотличимо от «не объявляли»: `MergedAnnotation`
     * сравнивает значение, а собранная springdoc операция в обоих случаях отдаёт `null`.
     * Отличить можно только там, где видно написанное, то есть здесь.
     */
    @Test
    fun `каждая операция объявляет текст и требование аутентификации`() {
        controllers.forEach { file ->
            val lines = Files.readString(file).lines()
            lines.forEachIndexed { index, line ->
                val method = Regex("^    fun (\\w+)\\(").find(line) ?: return@forEachIndexed

                // Блок аннотаций — непрерывные строки над `fun`: сама аннотация, её
                // продолжение или закрывающая скобка многострочной. KDoc начинается с пяти
                // пробелов и подъём останавливает.
                var start = index
                while (start > 0 && lines[start - 1].let {
                        it.startsWith("    @") || it.startsWith("        ") || it == "    )"
                    }
                ) {
                    start--
                }
                // Берётся именно кусок @Operation: `description` есть и у каждого @ApiResponse.
                val operation = lines.subList(start, index).joinToString("\n")
                    .substringAfter("@Operation(", "")
                    .substringBefore("\n    @")

                val missing = buildList {
                    if ("summary = " !in operation) add("summary")
                    if ("description = " !in operation) add("description")
                    // Умолчания на уровне документа нет: промолчавшая операция станет открытой.
                    if ("security = " !in operation) add("security")
                }
                assertTrue(
                    missing.isEmpty(),
                    "${file.name}: у операции ${method.groupValues[1]} в @Operation нет " +
                        "${missing.joinToString(", ")} — объявляется явно у каждой"
                )
            }
        }
    }
}
