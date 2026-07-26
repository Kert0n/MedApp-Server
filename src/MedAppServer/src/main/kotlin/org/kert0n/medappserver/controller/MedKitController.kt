package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.slf4j.Logger
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
    private val medKitService: MedKitService,
    private val logger: Logger = LoggerFactory.getLogger(MedKitController::class.java),
    private val medKitDrugServices: MedKitDrugServices
) {
    data class MedKitCreatedResponse(
        @NotNull
        @Schema(description = "Created medkit ID")
        val id: UUID
    )

    data class AddUserRequest(
        @NotNull
        @Schema(description = "User ID to share medkit with")
        val userId: UUID
    )

    @Schema(description = "Join medkit request")
    data class JoinMedKitRequest(
        @NotBlank
        @Schema(description = "Share key to join medkit", example = "share-key-123")
        val key: String
    )


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
        val medKit = medKitService.createNew(authentication.userId)
        return MedKitCreatedResponse(medKit.id)
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
        val medKit = medKitService.findByIdForUser(id, authentication.userId)
        return medKitDrugServices.toMedKitDTO(medKit)
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
        return medKitService.findMedKitSummaries(authentication.userId)
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
        return medKitService.generateMedKitShareKey(medKitId, authentication.userId)
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
        val medKit = medKitService.joinMedKitByKey(request.key, authentication.userId)
        return medKitDrugServices.toMedKitDTO(medKit)
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
        medKitDrugServices.removeUserFromMedKit(id, authentication.userId)
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
        medKitDrugServices.delete(id, authentication.userId, transferToMedKitId)
    }
}

@Schema(description = "Medkit with drugs")
data class MedKitDTO(
    @Schema(description = "Medkit ID")
    val id: UUID,
    @Schema(description = "Drugs in medkit")
    val drugs: Set<DrugDTO>
)

data class MedKitSummaryDTO(
    @NotNull
    @Schema(description = "Medkit ID")
    val id: UUID,
    @NotNull
    @Schema(description = "Number of users in medkit")
    val userCount: Long,
    @NotNull
    @Schema(description = "Number of drugs in medkit")
    val drugCount: Long
)
