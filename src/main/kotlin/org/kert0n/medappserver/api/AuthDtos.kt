package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Что отдаёт регистрация: логин и ключ, которыми потом берут токен.
 *
 * Ключ показывается один раз — на сервере от него остаётся только хеш.
 */
@Schema(description = "Registration response with generated credentials")
@Serializable
data class RegisterResponse(
    @Schema(description = "Generated login identifier")
    val login: Uuid,
    @Schema(description = "Generated secret key for authentication")
    val key: String
)

/**
 * Только сам токен.
 *
 * Срок жизни уже в claim `exp`, дублировать его в обёртке — два источника одного факта. Схема
 * (`Bearer`) одна и зафиксирована в OpenAPI, от ответа к ответу не меняется.
 */
@Schema(description = "Issued access token")
@Serializable
data class TokenResponse(
    @Schema(description = "JWT access token")
    val accessToken: String
)
