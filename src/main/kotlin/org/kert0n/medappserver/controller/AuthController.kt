package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.kert0n.medappserver.api.RegisterRequest
import org.kert0n.medappserver.api.TokenResponse
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.application.AuthApplicationService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Authentication", description = "Public endpoints for registration and token issuance")
class AuthController(private val auth: AuthApplicationService) {

    /**
     * Учётные данные придумывает клиент, как и идентификаторы аптечек и упаковок: они известны до
     * отправки, поэтому потерянный ответ не оставляет учётки, в которую никто не войдёт.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        security = [],
        summary = "Register a new user",
        description = "Creates a user with the login and password chosen by the client. The credentials are " +
            "known before the request is sent, so a lost response is safe to repeat: the repeat answers 409, " +
            "and a token issued with the same credentials confirms the account is the caller's own."
    )
    @ApiResponse(responseCode = "201", description = "User registered")
    @ApiResponse(responseCode = "400", description = "Invalid login or password", content = [Content()])
    @ApiResponse(responseCode = "403", description = "Invalid registration secret", content = [Content()])
    @ApiResponse(responseCode = "409", description = "The login is already taken", content = [Content()])
    @ApiResponse(responseCode = "429", description = "Too many registration attempts", content = [Content()])
    fun register(
        request: HttpServletRequest,
        @Parameter(description = "Shared registration secret", required = true, example = "dev-secret")
        @RequestHeader("X-Registration-Token") token: String,
        @SwaggerRequestBody(description = "Credentials chosen by the client")
        @Valid @RequestBody credentials: RegisterRequest
    ) {
        auth.register(token, request.remoteAddr, credentials.login, credentials.password)
    }

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
