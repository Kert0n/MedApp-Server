package org.kert0n.medappserver.probe

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.assertEquals
import org.jetbrains.exposed.spring.SpringTransactionManager
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.tables.*
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Схема, выведенная из объектов `Table`, обязана нести те же ключи и каскады, что и прежняя.
 *
 * Составные ключи брони — сердце #94: «брони без доступа не существует» держит именно
 * `reservations_membership_fkey`, а переезд пачки тянет копию `med_kit_id` через
 * `ON UPDATE CASCADE`. Если это не воспроизводится, переезд не состоялся.
 */
class ExposedSchemaProbe {

    @Test
    fun `ключи и каскады те же`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl; username = pg.username; password = pg.password
            })
            val tx = TransactionTemplate(SpringTransactionManager(ds))

            tx.execute {
                SchemaUtils.create(
                    Users, MedKits, MedKitMemberships, FormTypes, QuantityUnits,
                    Drugs, Reservations, DrugTemplates
                )
            }

            val rules = tx.execute {
                val out = mutableMapOf<String, String>()
                TransactionManager.current().connection.prepareStatement(
                    """
                    SELECT constraint_name, delete_rule, update_rule
                    FROM information_schema.referential_constraints
                    WHERE constraint_schema = current_schema()
                    """.trimIndent(), false
                ).executeQuery().let { rs ->
                    while (rs.next()) {
                        out[rs.getString(1)] = "${rs.getString(2)}/${rs.getString(3)}"
                    }
                }
                out
            }!!

            rules.toSortedMap().forEach { (name, rule) -> println("ЗАМЕР ключ $name → $rule") }

            assertEquals("CASCADE/CASCADE", rules["reservations_drug_med_kit_fkey"], "переезд пачки тянет бронь")
            assertEquals("CASCADE/NO ACTION", rules["reservations_membership_fkey"], "нет членства — нет брони")
            assertEquals("CASCADE/NO ACTION", rules["user_drugs_med_kit_fkey"])
            assertEquals("CASCADE/NO ACTION", rules["user_med_kits_med_kit_fkey"])
            assertEquals("NO ACTION/NO ACTION", rules["user_med_kits_user_fkey"], "удаление человека не каскадит")
        }
    }
}
