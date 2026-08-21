package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `EntityManager` в проде не используется. Совсем и ни для чего.
 *
 * Это механика тестовой оснастки: `flush`, `clear`, `detach`, `refresh`, `getReference` —
 * способы подогнать состояние persistence context под ожидания. В рабочем коде они означают,
 * что запись выстроена вокруг того, когда именно Hibernate что-то сделает, — а такой код
 * ломается от версии к версии и не читается как правило.
 *
 * Работа с базой идёт через `db/repository`: JPQL, массовый DML, `save`. Лишний запрос дешевле,
 * чем расчёт на тонкости жизненного цикла сущностей.
 *
 * Тестам это не запрещено: там подгонка состояния и есть цель.
 */
class NoEntityManagerTest {

    @Test
    fun `рабочий код не трогает EntityManager`() {
        val offenders = Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver")).asSequence()
            .filter { it.name.endsWith(".kt") }
            .filter { "EntityManager" in Files.readString(it) }
            .map { it.name }
            .toList()

        assertEquals(
            emptyList(), offenders,
            "EntityManager — оснастка для тестов: в проде запись не должна стоять на том, " +
                "когда именно Hibernate синхронизирует контекст"
        )
    }
}
