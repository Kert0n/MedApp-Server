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
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
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
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator
) {

    private val logger = LoggerFactory.getLogger(MedKitController::class.java)

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Kit created")
    fun createMedKit(authentication: Authentication): ResponseEntity<MedKitCreatedDTO> {
        logger.debug("POST /v1/med-kits by user {}", authentication.userId)
        val created = medKitService.create(authentication.userId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Preconditions.etag(created.version))
            .body(MedKitCreatedDTO(created.id, created.version))
    }

    /**
     * Общего тега у списка нет: он менялся бы от любой чужой аптечки, и предъявлять его было
     * бы нечему. Версия каждой аптечки при этом в выдаче есть — с ней можно выйти или удалить,
     * не читая аптечку отдельным запросом.
     */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Kits returned")
    fun listMedKits(authentication: Authentication): Set<MedKitSummaryDTO> {
        logger.debug("GET /v1/med-kits by user {}", authentication.userId)
        return medKitService.overviews(authentication.userId).map { it.toDto() }.toSet()
    }

    @GetMapping("/{medKitId}")
    @ApiResponse(responseCode = "200", description = "Kit found")
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    fun getMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID
    ): ResponseEntity<MedKitDTO> {
        logger.debug("GET /v1/med-kits/{} by user {}", medKitId, authentication.userId)
        val medKit = medKitDrugOrchestrator.medKitWithDrugs(medKitId, authentication.userId)
        return Preconditions.withEtag(medKit.version, medKit)
    }

    /**
     * Приглашение — подчинённый ресурс аптечки, а не действие «share» над ней.
     *
     * Ключ живёт ограниченное время и в течение него принимается многократно: это
     * приглашение, а не одноразовый токен.
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

    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Kit deleted for everyone")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Membership changed while the request was in flight", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Parameter(description = "Kit to move the drugs into instead of discarding them")
        @RequestParam(required = false) targetMedKitId: UUID?
    ) {
        logger.debug("DELETE /v1/med-kits/{} by user {}, target {}", medKitId, authentication.userId, targetMedKitId)
        medKitDrugOrchestrator.delete(
            medKitId = medKitId,
            userId = authentication.userId,
            expectedVersion = Preconditions.requiredVersion(ifMatch),
            transferToMedKitId = targetMedKitId
        )
    }
}

/**
 * Членство — собственный ресурс, а не действие над аптечкой.
 *
 * Раньше это были `POST /med-kit/join` и `DELETE /med-kit/{id}/leave`: два глагола вместо
 * создания и удаления одной и той же связи.
 */
@RestController
@RequestMapping("/v1/med-kit-memberships")
@Tag(name = "Medicine kit memberships", description = "Participation of the caller in shared kits")
class MedKitMembershipController(
    private val medKitService: MedKitService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator
) {

    private val logger = LoggerFactory.getLogger(MedKitMembershipController::class.java)

    /** Вступление предусловия не требует: оно ничего не перезаписывает, а добавляет себя. */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Joined")
    @ApiResponse(responseCode = "404", description = "Invitation expired or unknown", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Kit was deleted or changed while joining", content = [Content()])
    fun joinMedKit(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Invitation key")
        @Valid @RequestBody request: MembershipCreateRequest
    ): ResponseEntity<MedKitDTO> {
        logger.debug("POST /v1/med-kit-memberships by user {}", authentication.userId)
        val joined = medKitService.joinByInvitation(request.key, authentication.userId)
        val medKit = medKitDrugOrchestrator.medKitWithDrugs(joined.id, authentication.userId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Preconditions.etag(medKit.version))
            .body(medKit)
    }

    @DeleteMapping("/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Left the kit")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Kit does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Membership changed while the request was in flight", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun leaveMedKit(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/med-kit-memberships/{} by user {}", medKitId, authentication.userId)
        medKitDrugOrchestrator.leaveMedKit(medKitId, authentication.userId, Preconditions.requiredVersion(ifMatch))
    }
}
