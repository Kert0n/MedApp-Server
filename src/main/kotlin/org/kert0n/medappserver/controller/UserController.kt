package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.application.UserApplicationService
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users")
@Tag(name = "User", description = "The authenticated user")
class UserController(private val users: UserApplicationService) {

    private val logger = LoggerFactory.getLogger(UserController::class.java)

    /**
     * `me`, а не идентификатор в пути: другого пользователя здесь всё равно не посмотреть,
     * а путь с идентификатором обещал бы обратное.
     */
    @GetMapping("/me")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Get the caller's snapshot",
        description = "Returns the caller identifier with every accessible medicine kit and its drugs, for sync."
    )
    @ApiResponse(responseCode = "200", description = "Snapshot returned", content = [Content(schema = Schema(implementation = UserSnapshotDTO::class))])
    fun getSnapshot(authentication: Authentication): UserSnapshotDTO {
        logger.debug("GET /v1/users/me by user {}", authentication.userId)
        return users.snapshot(authentication.userId)
    }
}
