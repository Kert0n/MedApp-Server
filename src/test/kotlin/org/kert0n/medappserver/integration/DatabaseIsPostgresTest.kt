package org.kert0n.medappserver.integration

import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Страховка от ложно-зелёного прогона: `@PostgresIntegrationTest` собран из мета-аннотаций, и
 * если Spring перестанет их подхватывать, тесты молча уедут на H2 из базового
 * `application.properties` — зелёные и проверяющие не то, что в проде.
 *
 * Поэтому здесь утверждается сам факт: под тестом Postgres той версии, что в compose.
 */
@PostgresIntegrationTest
class DatabaseIsPostgresTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `тесты идут против настоящего Postgres`() {
        dataSource.connection.use { connection ->
            val meta = connection.metaData
            assertEquals(
                "PostgreSQL",
                meta.databaseProductName,
                "интеграционные тесты уехали на ${meta.databaseProductName}; " +
                    "проверьте, что @PostgresIntegrationTest ещё работает"
            )
            assertEquals(
                18,
                meta.databaseMajorVersion,
                "версия Postgres расходится с продовой из compose: ${meta.databaseProductVersion}"
            )
        }
    }

    @Test
    fun `pg_trgm доступен`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT similarity('aspirin', 'aspirn')").use { rs ->
                    assertTrue(rs.next(), "similarity() не выполнился")
                    assertTrue(rs.getDouble(1) > 0.0, "similarity() вернул 0 — расширение не то")
                }
            }
        }
    }
}

/**
 * То же самое, но **без** `@PostgresIntegrationTest`.
 *
 * Контейнер обязан быть один на весь набор. Достанься он только через импорт, класс с голым
 * `@SpringBootTest` ушёл бы на `jdbc:tc:` из `application.properties` и получил свой контейнер
 * другой версии: набор шёл бы на двух Postgres сразу, а проверка выше стояла бы на «хорошем» и
 * этого не видела.
 *
 * Этот тест смотрит с той стороны: если контейнер снова перестанет доставаться всем, здесь
 * окажется не та версия — или не окажется базы вовсе.
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseIsSharedTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `тест без импорта конфигурации получает тот же Postgres`() {
        dataSource.connection.use { connection ->
            val meta = connection.metaData
            assertEquals("PostgreSQL", meta.databaseProductName)
            assertEquals(
                18,
                meta.databaseMajorVersion,
                "класс без @PostgresIntegrationTest уехал на ${meta.databaseProductVersion}: " +
                    "контейнер снова достаётся не всем"
            )
        }
    }
}
