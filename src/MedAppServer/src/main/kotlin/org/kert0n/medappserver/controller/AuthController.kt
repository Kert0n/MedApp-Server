package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.kert0n.medappserver.api.RegisterResponse
import org.kert0n.medappserver.config.RegistrationProperties
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.SecurityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Authentication", description = "Public endpoints for registration and token issuance")
class AuthController(
    // Пригодность самого значения — условие старта приложения, а не поведение эндпоинта,
    // поэтому проверки живут в RegistrationProperties и ProdSecretsGuard. Сюда секрет
    // приходит уже непустым: без этого контекст не поднимется.
    private val registrationProperties: RegistrationProperties,
    private val userService: UserService,
    private val securityService: SecurityService
) {

    @PostMapping("/register")
    @Operation(
        summary = "Register a new user",
        description = "Creates a new user and returns generated credentials.",
        security = []
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "User registered",
                content = [Content(schema = Schema(implementation = RegisterResponse::class))]
            ),
            ApiResponse(responseCode = "403", description = "Invalid registration secret", content = [Content()]),
            ApiResponse(responseCode = "429", description = "Too many registration attempts", content = [Content()])
        ]
    )
    fun register(
        request: HttpServletRequest,
        @Parameter(description = "Shared registration secret", required = true, example = "dev-secret")
        @RequestHeader("X-Registration-Token") token: String
    ): RegisterResponse {
        // Validate the shared secret first to avoid exposing rate-limit status to unauthorized callers.
        // Constant-time: `!=` stops at the first differing character, so response time would
        // reveal how long a correct prefix was.
        if (!securityService.secretsMatch(token, registrationProperties.secret)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid secret")
        }
        // Лимит по адресу и выпуск учётных данных — внутри сервиса. Здесь остаётся только
        // проверка секрета: она аутентифицирует сам запрос, то есть относится к границе.
        //
        // Порядок сохранён: секрет проверяется раньше лимита, чтобы состояние счётчика не
        // утекало тем, кто секрета не знает. Отказ по лимиту — 429, а не 504: за Caddy
        // пятисотый класс читается как «бэкенд не ответил», и отказ клиенту попадал в
        // алерты как авария инфраструктуры.
        val credentials = userService.registerNewUser(request.remoteAddr)
        return RegisterResponse(credentials.login, credentials.key)
    }

    @GetMapping("/login")
    @Operation(
        summary = "Issue JWT token",
        description = "Uses HTTP Basic authentication and returns a JWT access token.",
        security = []
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "JWT token issued",
                content = [Content(schema = Schema(implementation = String::class))]
            ),
            ApiResponse(responseCode = "401", description = "Invalid credentials", content = [Content()])
        ]
    )
    fun login(authentication: Authentication): String =
        securityService.generateToken(authentication)
}
