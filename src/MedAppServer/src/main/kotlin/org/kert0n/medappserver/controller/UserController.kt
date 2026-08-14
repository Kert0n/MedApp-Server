package org.kert0n.medappserver.controller

import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.UserDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/v1/user")
@Tag(name = "User Data", description = "Endpoints for user profile and synchronization data")
class UserController(
    private val medKitDrugServices: MedKitDrugServices
) {

    private val logger = LoggerFactory.getLogger(UserController::class.java)

    @GetMapping
    @Operation(
        summary = "Get user snapshot",
        description = "Returns user identifier with all accessible medkits and drugs for sync."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "User snapshot retrieved",
                content = [Content(schema = Schema(implementation = UserDto::class))]
            ),
            ApiResponse(responseCode = "401", description = "Unauthorized", content = [Content()])
        ]
    )
    fun getAllDataForUser(authentication: Authentication): UserDto {
        logger.debug("GET /v1/user by user {}", authentication.userId)
        // Сборкой снимка занят оркестратор: два запроса при любом числе аптечек, и оба
        // внутри одной транзакции. Контроллер только раскладывает результат по DTO.
        val snapshot = medKitDrugServices.userSnapshot(authentication.userId)
        return UserDto(
            id = authentication.userId,
            medKits = snapshot.mapTo(mutableSetOf()) { it.medKit.toDto(it.drugs) }
        )
    }
}
