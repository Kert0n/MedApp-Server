package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

@PostgresIntegrationTest
class CascadeSchemaTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `foreign keys delete drug and medkit children in the database`() {
        val rules = jdbc.query(
            """
            SELECT constraint_name, delete_rule
            FROM information_schema.referential_constraints
            WHERE constraint_schema = current_schema()
              AND constraint_name IN (
                'usings_drug_fkey',
                'user_drugs_med_kit_fkey',
                'user_med_kits_med_kit_fkey'
              )
            """.trimIndent()
        ) { row, _ -> row.getString("constraint_name") to row.getString("delete_rule") }
            .toMap()

        assertEquals(
            mapOf(
                "usings_drug_fkey" to "CASCADE",
                "user_drugs_med_kit_fkey" to "CASCADE",
                "user_med_kits_med_kit_fkey" to "CASCADE"
            ),
            rules
        )
    }
}
