package org.kert0n.medappserver

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.sourcesIn

/**
 * Читающий метод не пишет.
 *
 * `readOnly = true` — не подсказка, а обещание базе: Postgres отвергнет запись в такой
 * транзакции. Нарушение даёт отказ на ровном месте в проде и молчит в тестах, где транзакция
 * заведена иначе, — то есть находится хуже всего.
 *
 * Проверяется по исходникам: важно, что написано рядом с методом, а не что вызвалось в
 * конкретном прогоне.
 */
class ReadOnlyMethodsTest {

    private val services: List<Path> =
        sourcesIn("services")

    @Test
    fun `метод под readOnly не зовёт запись хранилища`() {
        assertTrue(services.isNotEmpty(), "сервисов не найдено — тест смотрит не туда")

        val offenders = services.flatMap { file -> file.readText().offendingMethods(file.name) }

        assertTrue(
            offenders.isEmpty(),
            "читающий метод пишет: в проде это отказ на ровном месте, в тестах — молчание\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * Метод разбирается от своей аннотации до следующей: тела в Kotlin бывают и выражением, и
     * блоком, и считать скобки ради этого правила незачем.
     */
    private fun String.offendingMethods(fileName: String): List<String> {
        val lines = lines()
        return lines.withIndex()
            .filter { (_, line) -> line.contains("@Transactional") && line.contains("readOnly = true") }
            .mapNotNull { (start, _) ->
                val end = lines.drop(start + 1).indexOfFirst { it.contains("@Transactional") }
                    .let { if (it == -1) lines.size else start + 1 + it }
                val body = lines.subList(start, end).joinToString("\n")
                val writes = WRITES.filter { body.contains(it) }
                if (writes.isEmpty()) null else "$fileName:${start + 1} зовёт ${writes.joinToString(", ")}"
            }
    }

    private companion object {
        /** Имена пишущих методов хранилищ: другого способа писать у сервисов нет. */
        val WRITES = listOf(".insert(", ".save(", ".delete(", ".update(", ".deleteWhere(")
    }
}
