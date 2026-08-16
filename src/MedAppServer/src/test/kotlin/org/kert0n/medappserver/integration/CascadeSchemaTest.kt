package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

/**
 * Каскады проверяются в самой базе, а не только через JPA: bulk-удаление и удаление вне
 * приложения не проходят через persistence context, и без каскада на уровне FK они оставили бы
 * висящие строки планов и членства.
 */
@PostgresIntegrationTest
class CascadeSchemaTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `внешние ключи удаляют детей препарата и аптечки`() {
        val rules = jdbc.query(
            """
            SELECT constraint_name, delete_rule
            FROM information_schema.referential_constraints
            WHERE constraint_schema = current_schema()
              AND constraint_name IN (
                'usings_drug_fkey',
                'user_drugs_med_kit_fkey',
                'user_med_kits_med_kit_fkey',
                'usings_user_fkey',
                'user_med_kits_user_fkey'
              )
            """.trimIndent()
        ) { row, _ -> row.getString("constraint_name") to row.getString("delete_rule") }
            .toMap()

        assertEquals(
            mapOf(
                "usings_drug_fkey" to "CASCADE",
                "user_drugs_med_kit_fkey" to "CASCADE",
                "user_med_kits_med_kit_fkey" to "CASCADE",
                // Пользователь не удаляется каскадом намеренно: аптечки общие.
                "usings_user_fkey" to "NO ACTION",
                "user_med_kits_user_fkey" to "NO ACTION"
            ),
            rules
        )
    }
}
