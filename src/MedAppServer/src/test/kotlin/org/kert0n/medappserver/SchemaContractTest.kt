package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Схема — контракт создания с нуля, а не история изменений.
 *
 * Проект поднимает базу заново, поэтому ALTER и файлы миграций здесь не нужны: они только
 * расходились бы с CREATE TABLE и порождали два источника истины.
 */
class SchemaContractTest {

    @Test
    fun `схема создаёт базу с нуля без ALTER`() {
        val schema = Files.readString(SCHEMA)

        assertFalse(
            Regex("(?im)^\\s*ALTER\\s").containsMatchIn(schema),
            "схема описывает конечное состояние, менять её нужно правкой CREATE TABLE"
        )
    }

    @Test
    fun `количества хранятся точным типом`() {
        val schema = Files.readString(SCHEMA)

        // Сравнение по колонке и типу, а не по строке целиком: выравнивание в файле меняется
        // от соседних колонок, и тест не должен падать из-за пробелов.
        val columns = Regex("""(\w+)\s+numeric\(19, 6\)""").findAll(schema).map { it.groupValues[1] }.toSet()
        listOf("quantity", "planned_amount").forEach {
            assertTrue(it in columns, "в схеме нет $it numeric(19, 6)")
        }
        assertFalse(
            "double precision" in schemaWithoutComments(),
            "double precision вернул бы двоичную погрешность в остатки и планы"
        )
    }

    @Test
    fun `дети удаляются каскадом, а пользователь — нет`() {
        val schema = Files.readString(SCHEMA)

        listOf(
            "FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE",
            "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        ).forEach { assertTrue(it in schema, "в схеме нет каскада: $it") }

        listOf(
            "CONSTRAINT usings_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),",
            "CONSTRAINT user_med_kits_user_fkey FOREIGN KEY (user_id) REFERENCES users (id)\n"
        ).forEach {
            assertTrue(
                it in schema,
                "FK на users обязан остаться без каскада: удаление пользователя не должно " +
                    "молча выносить чужие данные из общей аптечки"
            )
        }
    }

    @Test
    fun `рабочие профили проверяют схему, а не правят её`() {
        listOf(
            "src/main/resources/application-dev.properties",
            "src/main/resources/application-mock-prod.properties"
        ).forEach { file ->
            assertTrue(
                "spring.jpa.hibernate.ddl-auto=validate" in Files.readString(Path.of(file)),
                "$file обязан использовать ddl-auto=validate"
            )
        }
    }

    /** Комментарии объясняют выбор типов и сами упоминают отвергнутые; проверяем только DDL. */
    private fun schemaWithoutComments(): String =
        Files.readString(SCHEMA).lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

    private companion object {
        val SCHEMA: Path = Path.of("db/schema.sql")
    }
}
