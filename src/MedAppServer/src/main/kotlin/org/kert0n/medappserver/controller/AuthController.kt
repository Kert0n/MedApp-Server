package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.*
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.RegistrationSecret
import org.kert0n.medappserver.services.security.SecurityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Authentication", description = "Public endpoints for registration and token issuance")
class AuthController(
    // Проверки секрета — в самом RegistrationSecret: заглушка в проде должна ронять старт
    // независимо от того, кто её читает.
    private val registrationSecret: RegistrationSecret,
    private val userService: UserService,
    private val securityService: SecurityService
) {


    @Schema(description = "Registration response with generated credentials")
    data class RegisterResponse(
        @Schema(description = "Generated login identifier")
        val login: UUID,
        @Schema(description = "Generated secret key for authentication")
        val key: String
    )

    @PostMapping("/register")
    @Operation(security = [])
    @ApiResponse(responseCode = "200", description = "User registered", content = [Content(schema = Schema(implementation = RegisterResponse::class))])
    @ApiResponse(responseCode = "403", description = "Invalid registration secret", content = [Content()])
    @ApiResponse(responseCode = "429", description = "Too many registration attempts", content = [Content()])
    fun register(
        request: HttpServletRequest,
        @Parameter(description = "Shared registration secret", required = true, example = "dev-secret")
        @RequestHeader("X-Registration-Token") token: String
    ): RegisterResponse {
        // Secret first, so rate-limit status is not exposed to unauthorized callers. Constant
        // time: `!=` stops at the first differing character and timing would leak the prefix.
        if (!securityService.secretsMatch(token, registrationSecret.value)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid secret")
        }
        // Rate limit by IP to reduce abuse without storing PII. 429, а не 504: превышен лимит
        // вызывающего, а не истёк срок вышестоящего сервиса.
        if (!securityService.validateRequest(request.remoteAddr)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registration request")
        }
        val login = UUID.randomUUID()
        val pwd: String = securityService.generateKey(32)
        userService.registerNewUser(login, pwd, request.remoteAddr)
        return RegisterResponse(login, pwd)
    }

    /**
     * Только сам токен.
     *
     * Срок жизни уже в claim `exp`, дублировать его в обёртке — два источника одного факта.
     * Схема (`Bearer`) одна и зафиксирована в OpenAPI, от ответа к ответу не меняется.
     */
    @Schema(description = "Issued access token")
    data class TokenResponse(
        @Schema(description = "JWT access token")
        val accessToken: String
    )

    /**
     * POST, а не GET: выдача расходует лимит попыток и создаёт токен, а GET разрешено повторять
     * и кешировать — вместе с уходящим в нём Basic-заголовком.
     */
    @PostMapping("/token")
    @Operation(security = [SecurityRequirement(name = OpenApiConfiguration.BASIC_SCHEME)])
    @ApiResponse(responseCode = "200", description = "JWT token issued", content = [Content(schema = Schema(implementation = TokenResponse::class))])
    @ApiResponse(responseCode = "401", description = "Invalid credentials", content = [Content()])
    @ApiResponse(responseCode = "429", description = "Too many token requests", content = [Content()])
    fun token(authentication: Authentication): TokenResponse =
        TokenResponse(securityService.generateToken(authentication.principal as User))
}
