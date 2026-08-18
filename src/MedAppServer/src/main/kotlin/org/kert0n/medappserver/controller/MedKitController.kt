package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/med-kits")
@Tag(name = "Medicine kits", description = "Shared medicine kits")
class MedKitController(
    private val medKitService: MedKitService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator,
    private val preconditions: Preconditions
) {

    private val logger = LoggerFactory.getLogger(MedKitController::class.java)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Kit created")
    fun createMedKit(authentication: Authentication): MedKitCreatedDTO {
        logger.debug("POST /v1/med-kits by user {}", authentication.userId)
        return MedKitCreatedDTO(medKitService.create(authentication.userId).id)
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Kits returned")
    fun listMedKits(authentication: Authentication): Set<MedKitSummaryDTO> {
        logger.debug("GET /v1/med-kits by user {}", authentication.userId)
        return medKitDrugOrchestrator.medKitSummaries(authentication.userId)
    }

    @GetMapping("/{medKitId}")
    @ApiResponse(responseCode = "200", description = "Kit found")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun getMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ): ResponseEntity<MedKitDTO> {
        logger.debug("GET /v1/med-kits/{} by user {}", medKitId, authentication.userId)
        return medKitDrugOrchestrator.medKitWithDrugs(medKitId, authentication.userId).withEtag()
    }

    /**
     * Приглашение — подчинённый ресурс аптечки, а не действие «share» над ней.
     *
     * Ключ живёт ограниченное время и принимается многократно: приглашение, не одноразовый токен.
     */
    @PostMapping("/{medKitId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Invitation created")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun createInvitation(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ): InvitationDTO {
        logger.debug("POST /v1/med-kits/{}/invitations by user {}", medKitId, authentication.userId)
        return InvitationDTO(medKitService.invite(medKitId, authentication.userId))
    }

    /** Предъявляется версия удаляемой аптечки: целевая — место назначения, а не то, что меняют. */
    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Kit deleted for everyone")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Kit membership has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?,
        @Parameter(description = "Kit to move the drugs into instead of discarding them")
        @RequestParam(required = false) targetMedKitId: UUID?
    ) {
        logger.debug("DELETE /v1/med-kits/{} by user {}, target {}", medKitId, authentication.userId, targetMedKitId)
        medKitDrugOrchestrator.delete(
            medKitId, authentication.userId, preconditions.requiredMatch(ifMatch), targetMedKitId
        )
    }
}

/** Членство — собственный ресурс: вход и выход это создание и удаление одной и той же связи. */
@RestController
@RequestMapping("/v1/med-kit-memberships")
@Tag(name = "Medicine kit memberships", description = "Participation of the caller in shared kits")
class MedKitMembershipController(
    private val medKitService: MedKitService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator,
    private val preconditions: Preconditions
) {

    private val logger = LoggerFactory.getLogger(MedKitMembershipController::class.java)

    /** Предусловия нет: вступающий состава ещё не видел, и предъявлять ему нечего. */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Joined")
    @ApiResponse(responseCode = "404", description = "Invitation expired or unknown", content = [Content()])
    fun joinMedKit(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Invitation key")
        @Valid @RequestBody request: MembershipCreateRequest
    ): ResponseEntity<MedKitDTO> {
        logger.debug("POST /v1/med-kit-memberships by user {}", authentication.userId)
        val joined = medKitService.joinByInvitation(request.key, authentication.userId)
        return medKitDrugOrchestrator.medKitWithDrugs(joined.id, authentication.userId).createdWithEtag()
    }

    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Left the kit")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Kit membership has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun leaveMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/med-kit-memberships/{} by user {}", medKitId, authentication.userId)
        medKitDrugOrchestrator.leaveMedKit(medKitId, authentication.userId, preconditions.requiredMatch(ifMatch))
    }
}
