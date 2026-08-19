package org.kert0n.medappserver

import jakarta.persistence.EntityManager
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * `db/schema.sql` описывает ту же базу, что и сущности.
 *
 * До этого теста файл никто не читал: интеграционные тесты поднимают схему из сущностей
 * (`ddl-auto=create-drop`), а `validate` включён только в рабочих профилях. Расхождение —
 * забытая колонка, другой тип, пропущенный внешний ключ — обнаруживалось бы при старте прода,
 * и никак иначе.
 *
 * Здесь база создаётся **этим файлом**, а приложение поднимается с `validate`: если схема и
 * сущности разошлись, контекст не встанет и тест упадёт. Проверка ровно та, что в проде, — не
 * сравнение текста, а суждение самого Hibernate.
 *
 * Цена — отдельный контейнер на прогон: конфигурация другая, поэтому и контекст свой.
 */
@SpringBootTest
@Import(SchemaValidatesTest.SchemaInitialisedContainer::class)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
    ]
)
class SchemaValidatesTest {

    @Autowired private lateinit var entityManager: EntityManager

    /**
     * Контекст поднялся — значит, `validate` прошёл. Запрос ниже добавляет к этому проверку,
     * что таблицы действительно созданы файлом, а не остались от кого-то ещё.
     */
    @Test
    fun `приложение стартует на схеме из файла`() {
        val tables = entityManager
            .createNativeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .resultList
            .map { it.toString() }

        listOf("users", "med_kits", "user_med_kits", "user_drugs", "reservations", "parsed_drugs")
            .forEach { assertTrue(it in tables, "таблицы $it нет в базе, созданной db/schema.sql") }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SchemaInitialisedContainer {

        /**
         * Схема кладётся в `/docker-entrypoint-initdb.d`, а не подаётся `withInitScript`:
         * последний читает из classpath, а файл лежит рядом с проектом и попадать в ресурсы
         * ради теста не должен — он часть поставки, а не тестовых данных.
         */
        @Bean
        @ServiceConnection
        fun schemaInitialisedPostgres(): PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18.3-trixie"))
                .withCopyFileToContainer(
                    MountableFile.forHostPath("db/schema.sql"),
                    "/docker-entrypoint-initdb.d/schema.sql"
                )
                .withEnv(
                    "POSTGRES_INITDB_ARGS",
                    "--encoding=UTF8 --lc-ctype=en_US.utf8 --lc-collate=en_US.utf8"
                )
    }
}
