package org.kert0n.medappserver.testutil

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.kert0n.medappserver.db.tables.APPLICATION_TABLES
import org.kert0n.medappserver.db.tables.SchemaSupplement
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Схема для тестов строится из того же, из чего порождается `db/schema.sql`.
 *
 * Своей копии `ALTER` здесь больше нет: копия успела отстать — индекс по `search_tsv` в тестовой
 * базе не создавался вовсе, и полнотекстовая ступень поиска работала без него.
 */
@Component
class TestSchema {

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun create() {
        val transaction = TransactionManager.current()
        SchemaSupplement.beforeTables.forEach { transaction.exec(it) }
        SchemaUtils.create(*APPLICATION_TABLES)
        SchemaSupplement.afterTables.forEach { transaction.exec(it) }
    }
}
