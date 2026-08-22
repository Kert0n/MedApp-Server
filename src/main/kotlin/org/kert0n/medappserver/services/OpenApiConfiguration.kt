package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.kert0n.medappserver.api.POSITIVE_QUANTITY_PATTERN
import org.kert0n.medappserver.api.PositiveQuantity
import org.springdoc.core.customizers.PropertyCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Схемы аутентификации и общие сведения о контракте.
 *
 * Требования на уровне документа нет намеренно: каждая операция объявляет свой `security`
 * сама. Умолчание здесь означало бы, что операция может о нём умолчать, а тогда «отвечает без
 * токена» и «забыли написать» — одно и то же: публичная регистрация унаследовала бы Bearer и
 * была бы документирована как защищённая.
 */
@Configuration
class OpenApiConfiguration {

    companion object {
        const val BEARER_SCHEME = "Bearer Authentication"
        const val BASIC_SCHEME = "Basic Authentication"
    }

    /**
     * Образец величины публикуется из той же метки, которая её и проверяет.
     *
     * `@Schema`, повешенный мета-аннотацией на своё ограничение, springdoc не читает —
     * измерено: тип поля становится `number`, а образец пропадает совсем. Поэтому схему полю
     * ставит эта настройка, по той же `@PositiveQuantity`: условие остаётся написанным один
     * раз, а не проверяется в одном месте и описывается в другом.
     */
    @Bean
    fun positiveQuantities(): PropertyCustomizer = PropertyCustomizer { property, type ->
        if (type?.ctxAnnotations?.any { it is PositiveQuantity } == true) {
            // `types`, а не `type`: контракт версии 3.1, и печатается из множества. Образец
            // разобран как число, пока тип не назван, — переписывается строкой следом.
            property.types = setOf("string")
            property.type = "string"
            property.pattern = POSITIVE_QUANTITY_PATTERN
            property.example = property.example?.toString()
        }
        property
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
