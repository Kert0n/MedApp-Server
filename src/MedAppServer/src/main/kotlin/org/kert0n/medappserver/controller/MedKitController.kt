package org.kert0n.medappserver.controller

import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.JoinMedKitRequest
import org.kert0n.medappserver.api.MedKitCreatedResponse
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/med-kit")
@Tag(name = "MedKit Management", description = "APIs for managing medicine kits")
class MedKitController(
    private val lifecycle: MedKitLifecycleService,
    private val queries: MedKitQueryService
) {

    private val logger = LoggerFactory.getLogger(MedKitController::class.java)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new medkit", description = "Creates a new medkit for the authenticated user")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Medkit created successfully"),
            ApiResponse(responseCode = "401", description = "Unauthorized", content = [Content()])
        ]
    )
    fun createNew(authentication: Authentication): MedKitCreatedResponse {
        logger.debug("POST /v1/med-kit by user {}", authentication.userId)
        return MedKitCreatedResponse(lifecycle.create(authentication.userId))
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get medkit by ID", description = "Retrieves a medkit if the user has access")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Medkit found"),
            ApiResponse(responseCode = "404", description = "Medkit not found or access denied", content = [Content()])
        ]
    )
    fun getMedKit(
        authentication: Authentication,
        @Parameter(description = "Medkit ID") @PathVariable id: UUID
    ): MedKitDTO {
        logger.debug("GET /v1/med-kit/{} by user {}", id, authentication.userId)
        return queries.getContent(authentication.userId, id).toDto()
    }

    @GetMapping
    @Operation(summary = "Get all medkits", description = "Returns summary info for all medkits accessible to the user")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Medkits retrieved",
                content = [Content(schema = Schema(implementation = MedKitSummaryDTO::class))]
            ),
            ApiResponse(responseCode = "401", description = "Unauthorized", content = [Content()])
        ]
    )
    fun getAllMedKits(authentication: Authentication): Set<MedKitSummaryDTO> {
        logger.debug("GET /v1/med-kit by user {}", authentication.userId)
        return queries.listForUser(authentication.userId).mapTo(linkedSetOf()) { it.toDto() }
    }

    @PostMapping("/{medKitId}/share")
    @Operation(
        summary = "Generate share key",
        description = "Generates a share key for a medkit. The key stays valid until it expires " +
            "(medkit.share.termInMinutes) and can be used more than once during that window, so " +
            "treat it as a short-lived invitation rather than a single-use token."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Share key generated"),
            ApiResponse(responseCode = "404", description = "Medkit not found or access denied", content = [Content()])
        ]
    )
    fun generateKeyToMedKit(
        authentication: Authentication,
        @Parameter(description = "Medkit ID") @PathVariable medKitId: UUID
    ): String {
        logger.debug("POST /v1/med-kit/{}/share by user {}", medKitId, authentication.userId)
        return lifecycle.createInvitation(authentication.userId, medKitId)
    }

    @PostMapping("/join")
    @Operation(summary = "Join medkit by share key", description = "Joins an existing medkit using a share key")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Successfully joined medkit"),
            ApiResponse(responseCode = "404", description = "Share key expired or invalid", content = [Content()])
        ]
    )
    fun joinMedKitByKey(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Join request")
        @Valid @RequestBody request: JoinMedKitRequest
    ): MedKitDTO {
        logger.debug("POST /v1/med-kit/join by user {}", authentication.userId)
        val medKitId = lifecycle.join(authentication.userId, request.key)
        return queries.getContent(authentication.userId, medKitId).toDto()
    }

    @DeleteMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave medkit", description = "Removes the authenticated user from the medkit")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "User removed from medkit"),
            ApiResponse(responseCode = "404", description = "Medkit not found", content = [Content()])
        ]
    )
    fun leaveMedKit(
        authentication: Authentication,
        @Parameter(description = "Medkit ID") @PathVariable id: UUID
    ) {
        logger.debug("DELETE /v1/med-kit/{}/leave by user {}", id, authentication.userId)
        lifecycle.leave(authentication.userId, id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Delete medkit",
        description = "Deletes the medkit for everyone who shares it, including its drugs and " +
            "the treatment plans of other participants. Use when the physical medicine kit has " +
            "ceased to exist as a shared thing — it was taken away or was damaged beyond use. " +
            "Pass transferToMedKitId to move the drugs into another of your medkits instead of " +
            "discarding them. To leave a shared medkit without destroying it, use " +
            "DELETE /v1/med-kit/{id}/leave."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Medkit deleted for all participants"),
            ApiResponse(responseCode = "404", description = "Medkit not found", content = [Content()])
        ]
    )
    fun deleteMedKit(
        authentication: Authentication,
        @Parameter(description = "Medkit ID") @PathVariable id: UUID,
        @Parameter(description = "Target medkit ID to transfer drugs")
        @RequestParam(required = false) transferToMedKitId: UUID?
    ) {
        logger.debug("DELETE /v1/med-kit/{} by user {}, transfer to: {}", id, authentication.userId, transferToMedKitId)
        lifecycle.delete(authentication.userId, id, transferToMedKitId)
    }
}
