package org.kert0n.medappserver.db.tables

/**
 * То в схеме, чего Exposed в DDL не выражает.
 *
 * Три вещи, и все про справочник: расширение для триграмм, две колонки, которые считает сама
 * база, и индексы с указанием класса операторов. Объектом, а не файлом-ресурсом: так это
 * компилируется вместе с остальным и не разъезжается с `Tables.kt` молча.
 *
 * Пользуются им двое — генератор `db/schema.sql` и тестовая оснастка. Одним объектом на обоих
 * потому, что своя копия у оснастки отстаёт молча: тесты идут по базе без индекса и ничего об
 * этом не говорят.
 *
 * Всё через `IF NOT EXISTS`: файл схемы применяется поверх дампа справочника, который часть
 * таблиц уже создал.
 */
object SchemaSupplement {

    /** Расширение нужно раньше таблиц: без него не создать индекс с триграммным opclass. */
    val beforeTables: List<String> = listOf("CREATE EXTENSION IF NOT EXISTS pg_trgm")

    /** Колонки и индексы справочника — после того, как `parsed_drugs` появилась. */
    val afterTables: List<String> = listOf(
        // Документ полнотекстового поиска. Конфигурация simple, а не russian: стемминг ломает
        // торговые названия и фамилии производителей, а искать нужно именно их написание.
        """
        ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_tsv tsvector
            GENERATED ALWAYS AS (
                to_tsvector('simple',
                            coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                            coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
            ) STORED
        """.trimIndent(),

        // Те же четыре поля одной строкой — для поиска по словам. Отдельно от search_tsv,
        // потому что триграммам нужен текст: в разобранном документе опечатку не найти.
        // Описание, категорию и страну сюда не берём: без весов совпадение в длинном описании
        // считалось бы наравне с совпадением в названии.
        """
        ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_text text
            GENERATED ALWAYS AS (
                coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                coalesce(active_substance, '') || ' ' || coalesce(manufacturer, '')
            ) STORED
        """.trimIndent(),

        // Точный многословный запрос сразу по четырём полям.
        """
        CREATE INDEX IF NOT EXISTS ix_parsed_drugs_search_tsv
            ON parsed_drugs USING gin (search_tsv)
        """.trimIndent(),

        // Поиск по словам с опечатками: оператор <% отбирает записи, в которых у слова запроса
        // есть похожий участок. Один индекс на всю склейку вместо четырёх по отдельным полям —
        // искать всё равно нужно «где-нибудь в записи», а не в конкретном поле.
        """
        CREATE INDEX IF NOT EXISTS ix_parsed_drugs_search_text_trgm
            ON parsed_drugs USING gin (search_text gin_trgm_ops)
        """.trimIndent()
    )
}
