package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Каскады проверяются в самой базе, а не только через JPA: bulk-удаление и удаление вне
 * приложения не идут через persistence context, и без каскада на FK оставили бы висящие брони
 * и членство.
 */
@PostgresIntegrationTest
class CascadeSchemaTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /**
     * Ключи страхуют правила, поэтому проверяются их действия, а не только наличие.
     *
     * `reservations_membership_fkey` — страховка под «выход снимает брони»: правило живёт в
     * коде, ключ не даёт его забыть. `reservations_drug_med_kit_fkey` — под «назначение
     * переезжает вместе с пачкой»: `ON UPDATE` тянет копию аптечки за настоящей.
     */
    @Test
    fun `внешние ключи удаляют детей препарата и аптечки`() {
        val rules = jdbc.query(
            """
            SELECT constraint_name, delete_rule, update_rule
            FROM information_schema.referential_constraints
            WHERE constraint_schema = current_schema()
              AND constraint_name IN (
                'reservations_drug_med_kit_fkey',
                'reservations_membership_fkey',
                'user_drugs_med_kit_fkey',
                'user_med_kits_med_kit_fkey',
                'user_med_kits_user_fkey'
              )
            """.trimIndent()
        ) { row, _ ->
            row.getString("constraint_name") to (row.getString("delete_rule") to row.getString("update_rule"))
        }.toMap()

        assertEquals(
            mapOf(
                // Пачки не стало — назначений на неё тоже; пачка переехала — копия аптечки едет с ней.
                "reservations_drug_med_kit_fkey" to ("CASCADE" to "CASCADE"),
                // Членства не стало — назначений этого человека в этой аптечке тоже.
                "reservations_membership_fkey" to ("CASCADE" to "NO ACTION"),
                "user_drugs_med_kit_fkey" to ("CASCADE" to "NO ACTION"),
                "user_med_kits_med_kit_fkey" to ("CASCADE" to "NO ACTION"),
                // Пользователь не удаляется каскадом намеренно: аптечки общие.
                "user_med_kits_user_fkey" to ("NO ACTION" to "NO ACTION")
            ),
            rules
        )
    }
}
