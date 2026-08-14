package org.kert0n.medappserver

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Интеграционный тест против настоящего Postgres в контейнере.
 *
 * Нужен там, где поведение зависит от СУБД, а H2 в режиме совместимости его не
 * воспроизводит:
 *  - `@Formula` у `Drug.totalPlannedAmount` — коррелированный подзапрос;
 *  - `FOR UPDATE` по запросу с join (какие именно строки блокируются);
 *  - `similarity()` из pg_trgm и экранирование `ILIKE`;
 *  - точность `NUMERIC` по scale — H2 и Postgres округляют по-разному.
 *
 * Тесты, которые мокают сервисы или проверяют только HTTP-слой, остаются на H2: контейнер
 * им ничего не добавляет, а время сборки увеличивает.
 *
 * Набор аннотаций один и тот же для всех таких тестов не только ради краткости: Spring
 * кеширует контексты по конфигурации, поэтому одинаковая аннотация означает один контейнер
 * на весь прогон вместо контейнера на класс.
 */
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
