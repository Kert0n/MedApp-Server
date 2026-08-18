package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Уровни не смешиваются.
 *
 * Оба правила ниже уже нарушались, и оба раза — незаметно. Хранилища оказались видны
 * оркестратору, а контроллеры разговаривали то с прикладным сервисом, то с сервисом агрегата,
 * выбирая по признаку «сколько агрегатов трогает операция» — признаку, которого из HTTP не
 * видно. Проверяется по исходникам, а не рефлексией: правило про **импорты**, а рефлексия
 * увидела бы только то, что осталось в байт-коде.
 *
 * Это узкий набор, охраняющий одну перестройку. Полные правила части 2 — за архитектурными
 * тестами следующего PR.
 */
class LayerBoundariesTest {

    private val sources: List<Path> =
        Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .toList()

    /**
     * Хранилище — граница агрегата, и знать о ней должен ровно один сервис.
     *
     * Иначе прикладной сценарий дотягивается до строк мимо правил агрегата — так оркестратор и
     * ходил в три хранилища сразу.
     */
    @Test
    fun `до хранилища дотягивается только сервис своего агрегата`() {
        val offenders = sources
            .filter { it.parent.name != "store" }
            .filter { Regex("^import org\\.kert0n\\.medappserver\\.db\\.store\\.", RegexOption.MULTILINE)
                .containsMatchIn(Files.readString(it)) }
            .filterNot { it.parent.name == "aggregate" }
            .map { it.name }

        assertEquals(
            emptyList(), offenders,
            "db/store виден только из services/aggregate: до агрегата ходят его сервисом"
        )
    }

    /**
     * У контроллера один доменный собеседник.
     *
     * Два и больше означают, что HTTP-слой выбирает между ними — а выбирать он может только по
     * внутреннему устройству прикладного слоя, которого знать не должен.
     */
    @Test
    fun `контроллер разговаривает с одним сервисом`() {
        val controllers = sources.filter { it.name.endsWith("Controller.kt") }
        assertTrue(controllers.isNotEmpty(), "контроллеры не найдены — тест смотрит не туда")

        controllers.forEach { file ->
            val text = Files.readString(file)
            Regex("^class (\\w+Controller)\\((.*?)\\) \\{", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .findAll(text)
                .forEach { declaration ->
                    // Тип берётся вместе с пакетом и обрезается: иначе полное имя вида
                    // `org.kert0n...DrugService` проскочило бы мимо проверки.
                    val services = Regex("val \\w+: ([\\w.]+)").findAll(declaration.groupValues[2])
                        .map { it.groupValues[1].substringAfterLast('.') }
                        .filter { it.endsWith("Service") }
                        // Безопасность и разбор предусловий — инфраструктура, а не домен.
                        .filterNot { it == "SecurityService" }
                        .toList()

                    assertTrue(
                        services.size <= 1,
                        "${declaration.groupValues[1]} держит ${services.size} сервисов ($services): " +
                            "на ресурс полагается один, иначе выбор между ними знает HTTP-слой"
                    )
                }
        }
    }

    /** Оркестратор возвращается только под цикл — и тогда объясняет, какой именно. */
    @Test
    fun `пакета orchestrators больше нет`() {
        assertTrue(
            !Files.exists(Path.of("src/main/kotlin/org/kert0n/medappserver/services/orchestrators")),
            "деление на «один агрегат» и «больше одного» — по свойству реализации, а не по сценарию"
        )
    }
}
