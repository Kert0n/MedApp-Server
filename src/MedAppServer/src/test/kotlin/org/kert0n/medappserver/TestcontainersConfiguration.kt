package org.kert0n.medappserver

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Контейнер один на весь набор.
 *
 * `@Configuration`, а не `@TestConfiguration`: второе не подхватывается сканированием и требует
 * `@Import` на каждом классе. Кто про импорт забывал, тот уходил на `jdbc:tc:` из
 * `application.properties` и поднимал **свой** контейнер другой версии — набор шёл на двух
 * разных Postgres сразу, при том что версия пришпилена как раз затем, чтобы этого не было.
 */
@Configuration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        // Версия пришпилена и совпадает с продовой из compose: с `latest` тесты молча меняли
        // бы СУБД под собой при каждом обновлении образа.
        return PostgreSQLContainer(DockerImageName.parse("postgres:18.3-trixie"))
            .withInitScript("init-pg-trgm.sql")
            // Локаль та же, что в compose: pg_trgm смотрит на LC_CTYPE, и в локали C кириллица
            // буквой не считается — триграммы из русского текста не извлекаются вовсе.
            .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --lc-ctype=en_US.utf8 --lc-collate=en_US.utf8")
    }

}
