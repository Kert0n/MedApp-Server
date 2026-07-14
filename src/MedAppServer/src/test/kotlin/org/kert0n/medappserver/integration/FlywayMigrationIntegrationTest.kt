package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    ]
)
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `fresh PostgreSQL database is migrated before Hibernate validation`() {
        val versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
            String::class.java
        )
        val quantityColumn = jdbcTemplate.queryForMap(
            """
            SELECT data_type, numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'user_drugs'
              AND column_name = 'quantity'
            """.trimIndent()
        )

        assertEquals(listOf("1", "2"), versions)
        assertEquals("numeric", quantityColumn["data_type"])
        assertEquals(19, (quantityColumn["numeric_precision"] as Number).toInt())
        assertEquals(6, (quantityColumn["numeric_scale"] as Number).toInt())
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine3.23"))

        @DynamicPropertySource
        @JvmStatic
        fun configureDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
        }
    }
}
