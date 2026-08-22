package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Домен не знает ни про HTTP, ни про контракт.
 *
 * Сейчас не знает, но ничто этого не держит. Одна `ResponseStatusException` в доменном
 * отказе — и правила предметной области начинают зависеть от кода ответа: поменять 409 на 412
 * станет невозможно, не тронув домен. Один импорт из `api` — и форма ответа протекла в
 * правила: DTO нельзя будет переименовать, не задев предметную область.
 *
 * Проверяется по исходникам, а не рефлексией: правило про импорты, и читать его надо в том
 * виде, в каком оно написано в файле.
 */
class DomainIndependenceTest {

    private val domain: List<Path> =
        Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver/domain")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `домен не знает про HTTP`() {
        assertTrue(domain.isNotEmpty(), "доменных файлов не найдено — тест смотрит не туда")

        val offenders = domain.flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) -> line.startsWith("import ") && HTTP.any { line.contains(it) } }
                .map { (number, line) -> "${file.name}:${number + 1} $line" }
        }

        assertTrue(
            offenders.isEmpty(),
            "домен потянулся к HTTP: код ответа — дело контроллера, а не правила:\n" +
                offenders.joinToString("\n")
        )
    }

    @Test
    fun `домен не знает про контракт`() {
        val offenders = domain.flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) -> line.startsWith("import org.kert0n.medappserver.api") }
                .map { (number, line) -> "${file.name}:${number + 1} $line" }
        }

        assertTrue(
            offenders.isEmpty(),
            "форма ответа протекла в правила: DTO нельзя переименовать, не задев домен:\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * Единственная разрешённая зависимость домена от фреймворка — та, что уже принята
     * осознанно: `User` реализует `UserDetails`, потому что пользователь и есть тот, кем его
     * представляет аутентификация. Она названа в части 2 и остаётся исключением, а не примером.
     */
    @Test
    fun `других зависимостей от Spring в домене нет`() {
        val offenders = domain.flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) ->
                    line.startsWith("import org.springframework") && ALLOWED.none { line.contains(it) }
                }
                .map { (number, line) -> "${file.name}:${number + 1} $line" }
        }

        assertTrue(
            offenders.isEmpty(),
            "домен зависит от Spring сверх принятого исключения про UserDetails:\n" +
                offenders.joinToString("\n")
        )
    }

    private companion object {
        /**
         * Контракт `UserDetails` целиком, а не только сам интерфейс: он требует отдавать
         * `GrantedAuthority`, и без этого типа реализовать его нельзя. Исключение названо
         * точно, чтобы под него не подпало что-нибудь ещё из `spring-security`.
         */
        val ALLOWED = listOf(
            "security.core.userdetails.UserDetails",
            "security.core.GrantedAuthority"
        )

        val HTTP = listOf(
            "org.springframework.http",
            "org.springframework.web",
            "jakarta.servlet",
            "ResponseStatus"
        )
    }
}
