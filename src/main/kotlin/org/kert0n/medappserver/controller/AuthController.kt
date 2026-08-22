package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.application.AuthApplicationService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Authentication", description = "Public endpoints for registration and token issuance")
class AuthController(private val auth: AuthApplicationService) {

    @Schema(description = "Registration response with generated credentials")
    @Serializable
    data class RegisterResponse(
        @Schema(description = "Generated login identifier")
        val login: Uuid,
        @Schema(description = "Generated secret key for authentication")
        val key: String
    )

    @PostMapping("/register")
    @Operation(
        security = [],
        summary = "Register a new user",
        description = "Creates a new user and returns generated credentials."
    )
    @ApiResponse(responseCode = "200", description = "User registered", content = [Content(schema = Schema(implementation = RegisterResponse::class))])
    @ApiResponse(responseCode = "403", description = "Invalid registration secret", content = [Content()])
    @ApiResponse(responseCode = "429", description = "Too many registration attempts", content = [Content()])
    fun register(
        request: HttpServletRequest,
        @Parameter(description = "Shared registration secret", required = true, example = "dev-secret")
        @RequestHeader("X-Registration-Token") token: String
    ): RegisterResponse {
        val credentials = auth.register(token, request.remoteAddr)
        return RegisterResponse(credentials.login, credentials.key)
    }

    /**
     * Только сам токен.
     *
     * Срок жизни уже в claim `exp`, дублировать его в обёртке — два источника одного факта.
     * Схема (`Bearer`) одна и зафиксирована в OpenAPI, от ответа к ответу не меняется.
     */
    @Schema(description = "Issued access token")
    @Serializable
    data class TokenResponse(
        @Schema(description = "JWT access token")
        val accessToken: String
    )

    /**
     * POST, а не GET: выдача расходует лимит попыток и создаёт токен, а GET разрешено повторять
     * и кешировать — вместе с уходящим в нём Basic-заголовком.
     */
    @PostMapping("/token")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BASIC_SCHEME)],
        summary = "Issue JWT token",
        description = "Uses HTTP Basic authentication and returns a JWT access token. The token carries its own " +
            "expiry in the `exp` claim; use it as `Authorization: Bearer <token>`."
    )
    @ApiResponse(responseCode = "200", description = "JWT token issued", content = [Content(schema = Schema(implementation = TokenResponse::class))])
    @ApiResponse(responseCode = "401", description = "Invalid credentials", content = [Content()])
    @ApiResponse(responseCode = "429", description = "Too many token requests", content = [Content()])
    fun token(authentication: Authentication): TokenResponse =
        TokenResponse(auth.issueToken(authentication.principal as User))
}
