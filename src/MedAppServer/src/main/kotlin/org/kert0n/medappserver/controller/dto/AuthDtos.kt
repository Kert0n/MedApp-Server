package org.kert0n.medappserver.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Registration response with generated credentials")
data class RegisterResponse(
    @Schema(description = "Generated login identifier")
    val login: UUID,
    @Schema(description = "Generated secret key; it cannot be recovered later")
    val key: String
)

@Schema(description = "Bearer access token")
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long
)
