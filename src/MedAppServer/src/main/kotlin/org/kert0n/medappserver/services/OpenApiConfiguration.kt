package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
            .components(
                Components()
                    .addSecuritySchemes(
                        BEARER_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                    )
                    // Выдача токена ходит по Basic, и без этой схемы её нельзя было вызвать
                    // из Swagger: диалог авторизации не предлагал места для логина и ключа,
                    // а запрос уходил без заголовка и получал 401.
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
