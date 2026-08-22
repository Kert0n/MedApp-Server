package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.tables.APPLICATION_TABLES
import org.kert0n.medappserver.db.tables.SchemaSupplement
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Держит `db/schema.sql` тем, что порождают объекты `Table`.
 *
 * Файл — производная, а не второй источник: схему описывает `Tables.kt` плюс `SchemaSupplement`,
 * а файл существует потому, что его монтируют в Postgres все три compose-файла. Живя порознь, эти
 * двое расходятся молча — на индекс, которого нет в одном из них, никто не жалуется.
 *
 * Тест ничего не утверждает о самих колонках: пересказ схемы на другом языке ломался бы от
 * любой её правки и ничего не доказывал. Он утверждает одно — закоммиченный файл не отстал.
 * Поэтому правка `Tables.kt` без перегенерации роняет сборку, а не уезжает тихо в прод.
 *
 * После намеренного изменения схемы:
 *
 *     ./gradlew test -DupdateSchema=true
 */
@PostgresIntegrationTest
class SchemaSnapshotTest {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `закоммиченная схема совпадает с порождённой`() {
        val generated = generate()

        if (System.getProperty("updateSchema") == "true") {
            Files.writeString(SNAPSHOT, generated)
            println("Перезаписал $SNAPSHOT из объектов Table")
            return
        }

        val committed = if (Files.exists(SNAPSHOT)) Files.readString(SNAPSHOT) else ""
        assertEquals(
            committed,
            generated,
            "$SNAPSHOT разошёлся с тем, что порождают Table. Если схему меняли намеренно, " +
                "перегенерируйте файл в том же коммите: ./gradlew test -DupdateSchema=true"
        )
    }

    /**
     * Файл не просто совпадает с порождённым — он ещё и создаёт базу.
     *
     * Проверка не пересказывает схему: она применяет тот самый артефакт, который монтируется в
     * Postgres. Ловит она порядок операторов — на нём я уже спотыкался: составной ключ брони
     * ссылается на уникальность `user_drugs`, и стоило выдать индексы общим хвостом после всех
     * таблиц, как схема переставала применяться с нуля.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `закоммиченная схема создаёт базу с нуля`() {
        val statements = Files.readString(SNAPSHOT)
            .lines().joinToString("\n") { it.substringBefore("--") }
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }

        transaction {
            exec("DROP SCHEMA IF EXISTS schema_snapshot_check CASCADE")
            exec("CREATE SCHEMA schema_snapshot_check")
            exec("SET search_path = schema_snapshot_check, public")
            try {
                statements.forEach { exec(it) }
            } finally {
                exec("SET search_path = public")
            }
            exec("DROP SCHEMA IF EXISTS schema_snapshot_check CASCADE")
        }
    }

    /**
     * Индексы таблицы идут сразу за ней, а не общим хвостом.
     *
     * Иначе схема не применяется с нуля: составной ключ брони ссылается на уникальность
     * `user_drugs`, а Exposed выдаёт её отдельным `ALTER` после всех таблиц.
     */
    private fun generate(): String = transaction {
        val statements = buildList {
            addAll(SchemaSupplement.beforeTables)
            APPLICATION_TABLES.forEach { table ->
                addAll(table.ddl.map { it.inColumns() })
                table.indices.forEach { index -> addAll(index.createStatement()) }
            }
            addAll(SchemaSupplement.afterTables)
        }
        HEADER + statements.joinToString("\n\n") { "$it;" } + "\n"
    }

    /**
     * Тело таблицы — по элементу на строку.
     *
     * Exposed отдаёт оператор одной строкой: у `user_drugs` это девять сотен символов, в которых
     * колонку от ограничения глазами не отделить. Файл читают люди — печатаем его так, как
     * написали бы руками.
     *
     * Правится только `CREATE TABLE`: у индексов и `ALTER` тело в одну короткую скобку, и
     * разворачивать там нечего. Рукописные операторы `SchemaSupplement` сюда не попадают —
     * они уже отформатированы, и трогать их незачем.
     */
    private fun String.inColumns(): String {
        if (!startsWith("CREATE TABLE") || !endsWith(")")) return this

        val body = substringAfter("(").dropLast(1)
        return substringBefore("(").trimEnd() + " (\n" +
            body.splitTopLevel().joinToString(",\n") { "    ${it.trim()}" } + "\n)"
    }

    /**
     * Режет по запятым верхнего уровня.
     *
     * Вложенные скобки остаются целыми: `DECIMAL(19, 6)`, `PRIMARY KEY (drug_id, user_id)`,
     * `CHECK (quantity > 0)`. Запятая внутри кавычек — тоже не разделитель: имя колонки или
     * значение по умолчанию про наш синтаксис ничего не знают.
     */
    private fun String.splitTopLevel(): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null

        forEach { symbol ->
            when {
                quote != null -> if (symbol == quote) quote = null
                symbol == '\'' || symbol == '"' -> quote = symbol
                symbol == '(' -> depth++
                symbol == ')' -> depth--
            }
            if (symbol == ',' && depth == 0 && quote == null) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(symbol)
            }
        }
        return parts + current.toString()
    }

    private companion object {
        // Тесты запускаются из каталога проекта.
        private val SNAPSHOT: Path = Path.of("db/schema.sql")

        private val HEADER = """
            -- Схема базы MedAppServer.
            --
            -- ФАЙЛ ПОРОЖДАЁТСЯ. Руками не правится: схему описывают объекты Table в
            -- src/main/kotlin/.../db/tables. После правки — ./gradlew test -DupdateSchema=true,
            -- и перегенерированный файл кладётся в тот же коммит; за этим следит
            -- SchemaSnapshotTest.
            --
            -- Применяется при инициализации Postgres в compose, поэтому на чистой машине схема
            -- появляется сама.

        """.trimIndent() + "\n"
    }
}
