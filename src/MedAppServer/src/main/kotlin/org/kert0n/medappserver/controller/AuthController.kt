package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.RegistrationSecret
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
        // Validate first so the rate-limit state is not disclosed to unauthorized callers.
        if (!securityService.secretsMatch(token, registrationSecret.value)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid secret")
        }
        // Registration throttling is keyed by a hash of the resolved client address.
        if (!securityService.validateRequest(request.remoteAddr)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registration request")
        }
        val login = UUID.randomUUID()
        val pwd: String = securityService.generateKey(32)
        userService.registerNewUser(login, pwd, request.remoteAddr)
        return RegisterResponse(login, pwd)
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
        securityService.generateToken(authentication.name)
}
