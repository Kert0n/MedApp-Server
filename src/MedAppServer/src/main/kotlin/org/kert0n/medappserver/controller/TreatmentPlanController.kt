package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.IntakeResultDTO
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanDTO
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.model.IntakePayload
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
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
@RequestMapping("/v1/treatment-plans")
@Tag(name = "Treatment plans", description = "Drug amounts reserved by the current user")
class TreatmentPlanController(
    private val queries: TreatmentPlanQueryService,
    private val commands: TreatmentPlanOrchestrator
) {
    @GetMapping
    @Operation(summary = "List own treatment plans")
    fun list(authentication: Authentication): List<TreatmentPlanDTO> =
        queries.listForUser(authentication.userId).map { it.toDto() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a treatment plan")
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: TreatmentPlanCreateRequest
    ): TreatmentPlanDTO = commands.create(
        authentication.userId,
        CreateTreatmentPlanCommand(request.drugId, request.plannedAmount)
    ).toDto()

    @GetMapping("/{drugId}")
    @Operation(summary = "Get own treatment plan for a drug")
    fun get(authentication: Authentication, @PathVariable drugId: UUID): TreatmentPlanDTO =
        queries.getForUser(authentication.userId, drugId).toDto()

    @PatchMapping("/{drugId}")
    @Operation(summary = "Change a treatment plan")
    fun patch(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: TreatmentPlanPatchRequest
    ): TreatmentPlanDTO = commands.patch(authentication.userId, drugId, request.plannedAmount).toDto()

    @DeleteMapping("/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a treatment plan")
    fun delete(authentication: Authentication, @PathVariable drugId: UUID) {
        commands.delete(authentication.userId, drugId)
    }
}

@RestController
@RequestMapping("/v1/intakes")
@Tag(name = "Intakes", description = "Idempotent planned drug intake")
class IntakeController(
    private val commands: IntakeOrchestrator
) {
    @PutMapping("/{intakeId}")
    @Operation(summary = "Register an idempotent intake")
    fun record(
        authentication: Authentication,
        @PathVariable intakeId: UUID,
        @Valid @RequestBody request: IntakeRequest
    ): IntakeResultDTO = commands.record(
        authentication.userId,
        intakeId,
        IntakePayload(request.drugId, request.quantity)
    ).toDto()
}
