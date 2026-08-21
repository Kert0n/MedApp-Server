package org.kert0n.medappserver.probe

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.spring.SpringTransactionManager
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Test
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * Замер, а не предположение: соблюдает ли `SpringTransactionManager` от Exposed propagation.
 *
 * На нём стоит всё разделение слоёв — фасад владеет транзакцией, ниже её только требуют
 * (`MANDATORY`). Если propagation не соблюдается, правило теряет зубы, и переезд обсуждается
 * заново.
 */
class ExposedTransactionProbe {

    object Probe : Table("probe") {
        val id = uuid("id")
        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun `MANDATORY требует чужую транзакцию и присоединяется к ней`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl; username = pg.username; password = pg.password
            })
            val manager = SpringTransactionManager(ds)

            val required = TransactionTemplate(manager)
            val mandatory = TransactionTemplate(manager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_MANDATORY
            }

            required.execute { SchemaUtils.create(Probe) }

            // 1. Вне транзакции MANDATORY обязан отказать — ровно как сейчас у Hibernate.
            val refused = assertFailsWith<IllegalTransactionStateException> {
                mandatory.execute { Probe.insert { it[id] = UUID.randomUUID() } }
            }
            println("ЗАМЕР 1 отказ вне транзакции: ${refused::class.simpleName}")

            // 2. Внутри чужой транзакции — присоединяется и пишет.
            required.execute { mandatory.execute { Probe.insert { it[id] = UUID.randomUUID() } } }
            val written = required.execute { Probe.selectAll().count() }
            println("ЗАМЕР 2 записано внутри чужой транзакции: $written")
            assertEquals(1L, written)

            // 3. Откат внешней транзакции уносит запись вложенной.
            runCatching {
                required.execute {
                    mandatory.execute { Probe.insert { it[id] = UUID.randomUUID() } }
                    error("падаем намеренно")
                }
            }
            val afterRollback = required.execute { Probe.selectAll().count() }
            println("ЗАМЕР 3 после отката внешней: $afterRollback")
            assertEquals(1L, afterRollback)
        }
    }
}
