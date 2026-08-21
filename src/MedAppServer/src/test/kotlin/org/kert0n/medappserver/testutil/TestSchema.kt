package org.kert0n.medappserver.testutil

import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.kert0n.medappserver.db.tables.*
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Схема для тестов выводится из объектов `Table`.
 *
 * `db/schema.sql` вторым источником в эксперименте не держим: если переезд состоится, схема
 * всё равно переписывается под новый слой, а миграций у проекта нет.
 */
@Component
class TestSchema {

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun create() {
        TransactionManager.current().exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
        SchemaUtils.create(
            Users, MedKits, MedKitMemberships, FormTypes, QuantityUnits,
            Drugs, Reservations, DrugTemplates
        )
        TransactionManager.current().exec(
            """
            ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_tsv tsvector
                GENERATED ALWAYS AS (
                    to_tsvector('simple',
                        coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                        coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
                ) STORED
            """.trimIndent()
        )
        TransactionManager.current().exec(
            """
            ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_text text
                GENERATED ALWAYS AS (
                    coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                    coalesce(active_substance, '') || ' ' || coalesce(manufacturer, '')
                ) STORED
            """.trimIndent()
        )
        TransactionManager.current().exec(
            """
            CREATE INDEX IF NOT EXISTS ix_parsed_drugs_search_text_trgm
                ON parsed_drugs USING gin (search_text gin_trgm_ops)
            """.trimIndent()
        )
    }
}
