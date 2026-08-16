package org.kert0n.medappserver

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/** Shared PostgreSQL context for locking, search and NUMERIC behaviour. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        // Схему для тестов создаёт Hibernate: db/schema.sql — про прод, а здесь важно, чтобы
        // структура шла ровно из сущностей.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Базовый application.properties в test-ресурсах настроен на H2; параметры
        // подключения подставляет @ServiceConnection, а диалект нужно переопределить явно.
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
    ]
)
annotation class PostgresIntegrationTest
