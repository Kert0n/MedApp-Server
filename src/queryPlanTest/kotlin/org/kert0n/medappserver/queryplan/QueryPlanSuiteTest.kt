package org.kert0n.medappserver.queryplan

import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired

/**
 * Набор поднимается и видит ту же базу, что и обычные тесты.
 *
 * Заглушка нужна: source set, который ещё ничего не проверяет, но уже запускается, отлаживать
 * проще, чем набор вместе с фикстуром на сорок тысяч строк.
 */
@PostgresIntegrationTest
class QueryPlanSuiteTest {

    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `набор идёт против того же Postgres`() {
        dataSource.connection.use { connection ->
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)
            assertEquals(
                18,
                connection.metaData.databaseMajorVersion,
                "планы зависят от версии: измерять надо ту же, что в проде"
            )
        }
    }
}
