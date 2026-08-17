package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.*
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/users")
@Tag(name = "User", description = "The authenticated user")
class UserController(
    private val medKitService: MedKitService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator
) {

    private val logger = LoggerFactory.getLogger(UserController::class.java)

    /**
     * `me`, а не идентификатор в пути: другого пользователя здесь всё равно не посмотреть,
     * а путь с идентификатором обещал бы обратное.
     */
    @GetMapping("/me")
    @ApiResponse(responseCode = "200", description = "Snapshot returned", content = [Content(schema = Schema(implementation = UserSnapshotDTO::class))])
    fun getSnapshot(authentication: Authentication): UserSnapshotDTO {
        logger.debug("GET /v1/users/me by user {}", authentication.userId)
        // Число запросов не зависит от того, сколько у человека аптечек и пачек.
        val drugsByMedKit = medKitDrugOrchestrator.drugsAccessibleTo(authentication.userId)
            .groupBy { it.medKitId }
        val medKits = medKitService.allOfUser(authentication.userId)
            .map { MedKitDTO(id = it.id, drugs = drugsByMedKit[it.id].orEmpty().toSet()) }
            .toSet()
        return UserSnapshotDTO(id = authentication.userId, medKits = medKits)
    }
}

@Schema(description = "Everything the caller can see, in one response")
data class UserSnapshotDTO(
    @Schema(description = "Caller identifier")
    val id: UUID,
    @Schema(description = "All medicine kits available to the caller")
    val medKits: Set<MedKitDTO>
)
