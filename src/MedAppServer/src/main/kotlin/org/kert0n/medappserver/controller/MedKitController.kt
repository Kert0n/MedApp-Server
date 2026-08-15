package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.MedKitContentDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitInvitationDTO
import org.kert0n.medappserver.api.MedKitMembershipCreateRequest
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
@Tag(name = "Medicine kits", description = "Shared medicine-kit lifecycle and content")
class MedKitController(
    private val queries: MedKitQueryService,
    private val commands: MedKitOrchestrator
) {
    @GetMapping("/med-kits")
    @Operation(summary = "List accessible medicine kits")
    fun list(authentication: Authentication): List<MedKitSummaryDTO> =
        queries.listForUser(authentication.userId).map { it.toDto() }

    @PostMapping("/med-kits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a medicine kit")
    fun create(authentication: Authentication): MedKitCreatedDTO =
        MedKitCreatedDTO(commands.create(authentication.userId).id)

    @GetMapping("/med-kits/{medKitId}")
    @Operation(summary = "Get medicine-kit content")
    fun get(authentication: Authentication, @PathVariable medKitId: UUID): MedKitContentDTO =
        queries.getContent(authentication.userId, medKitId).toDto()

    @DeleteMapping("/med-kits/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a medicine kit, optionally transferring its drugs")
    fun delete(
        authentication: Authentication,
        @PathVariable medKitId: UUID,
        @RequestParam(required = false) targetMedKitId: UUID?
    ) {
        commands.delete(authentication.userId, medKitId, targetMedKitId)
    }

    @PostMapping("/med-kits/{medKitId}/invitations")
    @Operation(summary = "Create an invitation")
    fun invite(authentication: Authentication, @PathVariable medKitId: UUID): MedKitInvitationDTO =
        MedKitInvitationDTO(commands.createInvitation(authentication.userId, medKitId).key)

    @PostMapping("/med-kit-memberships")
    @Operation(summary = "Join a medicine kit")
    fun join(
        authentication: Authentication,
        @Valid @RequestBody request: MedKitMembershipCreateRequest
    ): MedKitCreatedDTO = MedKitCreatedDTO(commands.join(authentication.userId, request.key).id)

    @DeleteMapping("/med-kit-memberships/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave a medicine kit")
    fun leave(authentication: Authentication, @PathVariable medKitId: UUID) {
        commands.leave(authentication.userId, medKitId)
    }
}
