package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/med-kits")
@Tag(name = "Medicine kits", description = "Shared medicine kits")
class MedKitController(
    private val medKits: MedKitApplicationService
) {

    private val logger = LoggerFactory.getLogger(MedKitController::class.java)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Create a medicine kit",
        description = "Creates a kit owned by nobody in particular."
    )
    @ApiResponse(responseCode = "201", description = "Kit created")
    fun createMedKit(authentication: Authentication): MedKitCreatedDTO {
        logger.debug("POST /v1/med-kits by user {}", authentication.userId)
        return medKits.create(authentication.userId)
    }

    @GetMapping
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "List medicine kits",
        description = "Returns counters for every kit of the caller, without loading their contents."
    )
    @ApiResponse(responseCode = "200", description = "Kits returned")
    fun listMedKits(authentication: Authentication): Set<MedKitSummaryDTO> {
        logger.debug("GET /v1/med-kits by user {}", authentication.userId)
        return medKits.summaries(authentication.userId)
    }

    @GetMapping("/{medKitId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Get a medicine kit",
        description = "Returns the kit with its drugs."
    )
    @ApiResponse(responseCode = "200", description = "Kit found")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun getMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ): MedKitDTO {
        logger.debug("GET /v1/med-kits/{} by user {}", medKitId, authentication.userId)
        return medKits.read(medKitId, authentication.userId)
    }

    /**
     * Приглашение — подчинённый ресурс аптечки, а не действие «share» над ней.
     *
     * Ключ живёт ограниченное время и принимается многократно: приглашение, не одноразовый токен.
     */
    @PostMapping("/{medKitId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Create an invitation",
        description = "Issues a key others can use to join the kit."
    )
    @ApiResponse(responseCode = "201", description = "Invitation created")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun createInvitation(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ): InvitationDTO {
        logger.debug("POST /v1/med-kits/{}/invitations by user {}", medKitId, authentication.userId)
        return medKits.invite(medKitId, authentication.userId)
    }

    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Delete a medicine kit",
        description = "Deletes the kit for every participant, including its packages and the reservations on them. " +
            "Use when the physical kit no longer exists as a shared thing. Pass targetMedKitId to move the drugs " +
            "into another kit of yours instead of discarding them. To leave a shared kit without destroying it, " +
            "delete your membership instead."
    )
    @ApiResponse(responseCode = "204", description = "Kit deleted for everyone")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun deleteMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @Parameter(description = "Kit to move the drugs into instead of discarding them")
        @RequestParam(required = false) targetMedKitId: UUID?
    ) {
        logger.debug("DELETE /v1/med-kits/{} by user {}, target {}", medKitId, authentication.userId, targetMedKitId)
        medKits.delete(medKitId, authentication.userId, targetMedKitId)
    }
}

/** Членство — собственный ресурс: вход и выход это создание и удаление одной и той же связи. */
@RestController
@RequestMapping("/v1/med-kit-memberships")
@Tag(name = "Medicine kit memberships", description = "Participation of the caller in shared kits")
class MedKitMembershipController(
    private val medKits: MedKitApplicationService
) {

    private val logger = LoggerFactory.getLogger(MedKitMembershipController::class.java)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Join a medicine kit",
        description = "Accepts an invitation and joins the kit."
    )
    @ApiResponse(responseCode = "201", description = "Joined")
    @ApiResponse(responseCode = "404", description = "Invitation expired or unknown", content = [Content()])
    fun joinMedKit(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Invitation key")
        @Valid @RequestBody request: MembershipCreateRequest
    ): MedKitDTO {
        logger.debug("POST /v1/med-kit-memberships by user {}", authentication.userId)
        return medKits.joinByInvitation(request.key, authentication.userId)
    }

    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Leave a medicine kit",
        description = "Removes the caller from the kit together with their reservations in it. The kit itself and " +
            "other participants stay."
    )
    @ApiResponse(responseCode = "204", description = "Left the kit")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun leaveMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ) {
        logger.debug("DELETE /v1/med-kit-memberships/{} by user {}", medKitId, authentication.userId)
        medKits.leave(medKitId, authentication.userId)
    }
}
