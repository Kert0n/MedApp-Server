package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Оглавление README не расходится с контрактом.
 *
 * Список маршрутов в README уже сгнил однажды: он описывал `/auth/login`, `/med-kit` и `/using`
 * спустя несколько итераций после того, как эти пути стали отдавать 404. Сгнил молча — стеречь
 * его было нечем.
 *
 * Сверяются **методы и пути**, и только они. Тексты, порядок и группировка свободны: README —
 * оглавление для человека, а не пересказ контракта, и правка описания не должна ронять сборку.
 *
 * Контракт берётся из закоммиченного `open-api.yaml`, который сам сверяется с приложением в
 * `OpenApiSnapshotTest`. Поэтому тест не поднимает Spring: цепочка «README → файл → приложение»
 * замкнута и без него.
 */
class ReadmeRoutesTest {

    @Test
    fun `маршруты README совпадают с опубликованными`() {
        val documented = readmeRoutes()
        val published = contractRoutes()

        assertTrue(documented.isNotEmpty(), "в README не нашлось ни одного маршрута — тест смотрит не туда")
        assertEquals(
            published, documented,
            "оглавление README разошлось с контрактом. Слева опубликованное, справа записанное " +
                "в README: маршрут добавили или переименовали, а оглавление осталось прежним."
        )
    }

    /** Строки вида «- `GET /v1/med-kits` — свои аптечки». */
    private fun readmeRoutes(): Set<String> =
        Regex("^- `($METHODS) (/v1\\S*)`", RegexOption.MULTILINE)
            .findAll(Files.readString(README))
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .toSet()

    /**
     * Пути и методы из контракта, без разбора YAML целиком.
     *
     * Разбирать документ ради двух уровней вложенности незачем: путь — единственная строка,
     * начинающаяся с двух пробелов и слеша, метод — с четырёх пробелов и имени глагола.
     */
    private fun contractRoutes(): Set<String> {
        val routes = mutableSetOf<String>()
        var path: String? = null
        Files.readAllLines(CONTRACT).forEach { line ->
            Regex("^  (/\\S+):$").find(line)?.let { path = it.groupValues[1] }
            Regex("^    (${METHODS.lowercase()}):$").find(line)?.let { verb ->
                path?.let { routes += "${verb.groupValues[1].uppercase()} $it" }
            }
        }
        return routes
    }

    private companion object {
        const val METHODS = "GET|POST|PUT|PATCH|DELETE"

        // Тесты запускаются из каталога проекта.
        val README: Path = Path.of("README.md")
        val CONTRACT: Path = Path.of("open-api.yaml")
    }
}
