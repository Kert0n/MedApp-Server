package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Схемы аутентификации и общие сведения о контракте.
 *
 * Требования на уровне документа нет намеренно: каждая операция объявляет свой `security`
 * сама. Умолчание здесь означало бы, что операция может о нём умолчать, а тогда «отвечает без
 * токена» и «забыли написать» становятся одним и тем же — и один раз уже стали: регистрация
 * наследовала Bearer и была документирована как защищённая.
 */
@Configuration
class OpenApiConfiguration {

    companion object {
        const val BEARER_SCHEME = "Bearer Authentication"
        const val BASIC_SCHEME = "Basic Authentication"
    }

    @Bean
    fun openApi(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("MedApp Server API")
                    .description("REST API for MedApp medicine kit synchronization and catalog search.")
                    .version("0.0.1")
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        BEARER_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                    )
                    // Выдача токена ходит по Basic: без этой схемы Swagger не предлагает
                    // места для логина и ключа, и запрос уходит без заголовка.
                    .addSecuritySchemes(
                        BASIC_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                            .description("Login is the identifier from registration, password is the key")
                    )
            )
    }
}
