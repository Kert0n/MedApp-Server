package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users")
@Tag(name = "Users", description = "Current-user synchronization data")
class UserController(
    private val queries: MedKitQueryService
) {
    @GetMapping("/me")
    @Operation(summary = "Get the current user snapshot")
    fun me(authentication: Authentication): UserSnapshotDTO =
        queries.getUserSnapshot(authentication.userId).toDto()
}
