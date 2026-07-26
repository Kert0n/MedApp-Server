package org.kert0n.medappserver

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        // Версия пришпилена и совпадает с продовой из compose. Было postgres:latest — то
        // есть тесты молча меняли СУБД под собой при каждом обновлении образа, и разница с
        // продом обнаруживалась бы уже в проде.
        return PostgreSQLContainer(DockerImageName.parse("postgres:18.3-trixie"))
            .withInitScript("init-pg-trgm.sql")
    }

}
