package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * На понятие — по одному типу в домене и по одному в отображении.
 *
 * Это правило и было нарушено в прошлой итерации: сумма броней жила и в упаковке, и рядом с
 * ней, и они разъезжались. Два типа про одно понятие — это два места, где выражено одно
 * правило, и рано или поздно они начинают говорить разное.
 *
 * Проверяются объявления, а не использование: важно, сколько типов заведено, а не сколько раз
 * их упомянули.
 */
class OneTypePerConceptTest {

    @Test
    fun `у понятия по одному типу в домене`() {
        CONCEPTS.forEach { concept ->
            val declared = declarationsIn("domain", concept)
            assertTrue(
                declared.size <= 1,
                "понятие «$concept» описано в домене несколькими типами — они разъедутся: $declared"
            )
        }
    }

    @Test
    fun `у понятия по одному типу в отображении`() {
        CONCEPTS.forEach { concept ->
            val declared = declarationsIn("api", concept)
            assertTrue(
                declared.size <= 1,
                "понятие «$concept» описано в контракте несколькими типами: $declared"
            )
        }
    }

    /**
     * Домен и контракт всё же разные типы, и это не нарушение: `Drug` про правила, `DrugDTO`
     * про форму ответа. Проверяется, что их по одному с каждой стороны, а не что тип один.
     */
    @Test
    fun `перечень понятий не устарел`() {
        val found = CONCEPTS.filter { declarationsIn("domain", it).isNotEmpty() }
        assertEquals(
            CONCEPTS.toSet(),
            found.toSet(),
            "понятие из перечня исчезло из домена: перечень надо поправить, а не оставлять"
        )
    }

    private fun declarationsIn(pkg: String, concept: String): List<String> =
        Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver/$pkg")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .flatMap { file ->
                file.readText().lines()
                    .mapNotNull { DECLARATION.find(it)?.groupValues?.get(1) }
                    .filter { it == concept || it == "$concept$SUFFIX" }
                    .map { "${file.name}: $it" }
            }
            .toList()

    private companion object {
        const val SUFFIX = "DTO"

        val DECLARATION = Regex("""^(?:data |sealed |value )?class (\w+)""")

        /**
         * Понятия, вокруг которых строится предметная область.
         *
         * Перечень ведётся руками: вывести его автоматически нельзя — понятие отличается от
         * вспомогательного типа только смыслом. Зато исчезнувшее понятие тест назовёт сам,
         * и перечень не превратится в список того, чего давно нет.
         */
        val CONCEPTS = listOf("Drug", "Reservation", "MedKit", "User", "Quantity")
    }
}
