package org.kert0n.medappserver.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {

    @Bean
    fun openApi(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("MedApp Server API")
                    .description("REST API for MedApp medicine kit synchronization and catalog search.")
                    .version("0.0.1")
            )
            // Требование по умолчанию — токен. Эндпоинты, которым он не нужен, снимают его
            // сами: регистрация — на свой секрет в заголовке, выдача токена — на Basic.
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
                    // Схема нужна, чтобы у GET /v1/auth/login в Swagger появилось поле ввода
                    // учётных данных. Без неё операция объявляла `security = []` — то есть
                    // «никакой аутентификации», — и UI не давал ввести логин с ключом вовсе:
                    // описание про HTTP Basic оставалось текстом, с которым нечего делать.
                    // Токен приходилось получать curl-ом и вставлять руками.
                    .addSecuritySchemes(
                        BASIC_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                            .description(
                                "Логин — идентификатор из ответа на регистрацию, пароль — выданный ключ."
                            )
                    )
            )
    }

    companion object {
        const val BEARER_SCHEME = "Bearer Authentication"
        const val BASIC_SCHEME = "Basic Authentication"
    }
}
