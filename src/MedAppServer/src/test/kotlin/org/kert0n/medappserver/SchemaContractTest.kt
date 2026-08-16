package org.kert0n.medappserver

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaContractTest {
    @Test
    fun `schema is a clean create contract with cascades and indexes`() {
        val schema = Files.readString(Path.of("db/schema.sql"))

        assertFalse(Regex("(?im)^\\s*ALTER\\s").containsMatchIn(schema))
        listOf(
            "numeric(19, 6)",
            "ix_user_drugs_med_kit_id",
            "ix_usings_user_id",
            "ix_usings_drug_id",
            "FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE",
            "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        ).forEach { contract -> assertTrue(contract in schema, "schema misses $contract") }
    }

    @Test
    fun `runtime PostgreSQL profiles validate rather than mutate schema`() {
        listOf(
            "src/main/resources/application-dev.properties",
            "src/main/resources/application-mock-prod.properties",
            "src/queryPlanTest/resources/application.properties"
        ).forEach { file ->
            assertTrue(
                "spring.jpa.hibernate.ddl-auto=validate" in Files.readString(Path.of(file)),
                "$file must use ddl-auto=validate"
            )
        }
    }
}
