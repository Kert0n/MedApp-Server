package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.IntakeResultDTO
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanDTO
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.IntakeService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
@Tag(name = "Treatment plans and intakes")
class UsingsController(
    private val reads: UsingService,
    private val intakes: IntakeService,
    private val commands: TreatmentPlanService
) {

    @GetMapping("/treatment-plans")
    fun list(authentication: Authentication): List<TreatmentPlanDTO> =
        reads.listForUser(authentication.userId).map { it.toDto() }

    @PostMapping("/treatment-plans")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: TreatmentPlanCreateRequest
    ): TreatmentPlanDTO =
        commands.create(authentication.userId, request.drugId, request.plannedAmount).toDto()

    @GetMapping("/treatment-plans/{drugId}")
    fun get(
        authentication: Authentication,
        @PathVariable drugId: UUID
    ): TreatmentPlanDTO = reads.getForUser(authentication.userId, drugId).toDto()

    @PatchMapping("/treatment-plans/{drugId}")
    fun patch(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: TreatmentPlanPatchRequest
    ): TreatmentPlanDTO =
        commands.patch(authentication.userId, drugId, request.plannedAmount).toDto()

    @DeleteMapping("/treatment-plans/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(authentication: Authentication, @PathVariable drugId: UUID) {
        commands.delete(authentication.userId, drugId)
    }

    @PutMapping("/intakes/{intakeId}")
    fun intake(
        authentication: Authentication,
        @PathVariable intakeId: UUID,
        @Valid @RequestBody request: IntakeRequest
    ): IntakeResultDTO {
        val outcome = intakes.record(
            authentication.userId,
            request.drugId,
            request.quantityConsumed,
            intakeId
        )
        return IntakeResultDTO(
            outcome.drugId,
            outcome.quantityConsumed,
            outcome.plan?.toDto()
        )
    }
}
