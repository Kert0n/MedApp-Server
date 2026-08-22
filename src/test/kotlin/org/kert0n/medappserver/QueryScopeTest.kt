package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.sourcesIn

/**
 * Доступ проверяет запрос, а не соглашение в коде.
 *
 * Чтение, отдающее чужие данные, само несёт `EXISTS` по членству. Проверка членства
 * отдельным шагом после чтения не считается: главный путь команд идёт по идентификатору, и
 * забытая проверка там ничем не ловится.
 *
 * Проверяется по исходникам, а не рефлексией: правило про **сигнатуры и текст запроса**.
 */
class QueryScopeTest {

    private val stores: List<Path> =
        sourcesIn("db/store", suffix = "Store.kt")

    /**
     * Чтения, отдающие данные владельца, обязаны принимать вызывающего.
     *
     * Исключения перечислены поимённо и объяснены — молчаливых не бывает.
     */
    @Test
    fun `чтение хранилища принимает вызывающего`() {
        assertTrue(stores.isNotEmpty(), "хранилищ не найдено — тест смотрит не туда")

        val offenders = stores.flatMap { file ->
            Regex("^    fun (\\w+)\\(([^)]*)\\)", RegexOption.MULTILINE)
                .findAll(Files.readString(file))
                .filter { it.groupValues[1].startsWith("find") }
                .filterNot { "userId" in it.groupValues[2] }
                .map { "${file.name}.${it.groupValues[1]}" }
        }.filterNot { it in ALLOWED }

        assertEquals(
            emptyList(), offenders,
            "чтение без вызывающего: доступ обязан проверять сам запрос, иначе проверку забудут"
        )
    }

    /** Кто читает по членству — читает его в запросе, а не сверяет потом в памяти. */
    @Test
    fun `скоуп выражен предикатом, а не проверкой после чтения`() {
        val services = sourcesIn("services")

        services.forEach { file ->
            val text = Files.readString(file)
            assertTrue(
                "requireMember(" !in text,
                "${file.name}: доменные ворота доступа вернулись. Не нашли запросом — значит нет."
            )
        }
    }

    private companion object {
        // Исключения только справочные: каталог и словари одинаковы для всех, владельца у
        // записи нет и скоупить нечем. `UserStore.findById` в списке не нужен: вызывающий там
        // и есть тот, кого ищут, и параметр называется так же.
        val ALLOWED = setOf(
            "CatalogueStore.kt.findTemplate",
            "CatalogueStore.kt.findQuantityUnit",
            "CatalogueStore.kt.findFormType"
        )
    }
}
