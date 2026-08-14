package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.JoinMedKitRequest
import org.kert0n.medappserver.api.MedKitCreatedResponse
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitInvitationDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
@Tag(name = "Medkits")
class MedKitController(
    private val lifecycle: MedKitLifecycleService,
    private val queries: MedKitQueryService
) {

    @GetMapping("/med-kits")
    fun list(authentication: Authentication): Set<MedKitSummaryDTO> =
        queries.listForUser(authentication.userId).mapTo(linkedSetOf()) { it.toDto() }

    @PostMapping("/med-kits")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(authentication: Authentication): MedKitCreatedResponse =
        MedKitCreatedResponse(lifecycle.create(authentication.userId))

    @GetMapping("/med-kits/{medKitId}")
    fun content(
        authentication: Authentication,
        @PathVariable medKitId: UUID
    ): MedKitDTO = queries.getContent(authentication.userId, medKitId).toDto()

    @DeleteMapping("/med-kits/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(authentication: Authentication, @PathVariable medKitId: UUID) {
        lifecycle.delete(authentication.userId, medKitId)
    }

    @PostMapping("/med-kits/{medKitId}/invitations")
    fun invitation(
        authentication: Authentication,
        @PathVariable medKitId: UUID
    ): MedKitInvitationDTO =
        MedKitInvitationDTO(lifecycle.createInvitation(authentication.userId, medKitId))

    @PostMapping("/med-kit-memberships")
    fun join(
        authentication: Authentication,
        @Valid @RequestBody request: JoinMedKitRequest
    ): MedKitDTO {
        val medKitId = lifecycle.join(authentication.userId, request.key)
        return queries.getContent(authentication.userId, medKitId).toDto()
    }

    @DeleteMapping("/med-kit-memberships/{medKitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(authentication: Authentication, @PathVariable medKitId: UUID) {
        lifecycle.leave(authentication.userId, medKitId)
    }
}
