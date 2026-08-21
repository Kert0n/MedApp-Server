package org.kert0n.medappserver

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Интеграционный тест против настоящего Postgres в контейнере.
 *
 * Нужен там, где поведение зависит от СУБД, а H2 в режиме совместимости его не воспроизводит:
 *  - `FOR UPDATE` по запросу с join — какие именно строки блокируются;
 *  - `similarity()` из pg_trgm и экранирование `ILIKE`;
 *  - точность `NUMERIC` по scale: H2 и Postgres округляют по-разному.
 *
 * Тесты, которые мокают сервисы или проверяют только HTTP-слой, остаются на H2. Набор аннотаций
 * одинаков не ради краткости: Spring кеширует контексты по конфигурации, поэтому это один
 * контейнер на прогон вместо контейнера на класс.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
annotation class PostgresIntegrationTest
