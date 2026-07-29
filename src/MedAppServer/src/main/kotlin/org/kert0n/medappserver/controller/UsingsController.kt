package org.kert0n.medappserver.controller

import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.api.UsingDTO
import org.kert0n.medappserver.api.UsingUpdateDTO
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.IntakeService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/using")
@Tag(name = "Treatment Plans", description = "Endpoints for treatment plans and intake tracking")
class UsingsController(
    private val usingService: UsingService,
    private val intakeService: IntakeService,
    private val treatmentPlanService: TreatmentPlanService
) {

    private val logger = LoggerFactory.getLogger(UsingsController::class.java)

    @GetMapping
    @Operation(summary = "Get all treatment plans", description = "Returns all planned drug usages for the user.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Treatment plans retrieved",
                content = [Content(schema = Schema(implementation = UsingDTO::class))]
            ),
            ApiResponse(responseCode = "401", description = "Unauthorized", content = [Content()])
        ]
    )
    fun getUsings(authentication: Authentication): List<UsingDTO> {
        logger.debug("GET /v1/using by user {}", authentication.userId)
        return usingService.listForUser(authentication.userId).map { it.toDto() }
    }

    @GetMapping("/drug/{drugId}")
    @Operation(summary = "Get treatment plan by drug", description = "Returns a treatment plan for a specific drug.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Treatment plan retrieved",
                content = [Content(schema = Schema(implementation = UsingDTO::class))]
            ),
            ApiResponse(responseCode = "404", description = "Treatment plan not found", content = [Content()])
        ]
    )
    fun getSpecificUsing(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable drugId: UUID
    ): UsingDTO {
        logger.debug("GET /v1/using/drug/{} by user {}", drugId, authentication.userId)
        return usingService.getForUser(authentication.userId, drugId).toDto()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create treatment plan", description = "Creates a planned usage for a drug.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Treatment plan created",
                content = [Content(schema = Schema(implementation = UsingDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid input", content = [Content()]),
            ApiResponse(responseCode = "409", description = "Treatment plan already exists", content = [Content()])
        ]
    )
    fun createUsing(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Treatment plan details")
        @Valid @RequestBody createDTO: UsingCreateDTO
    ): UsingDTO {
        logger.debug("POST /v1/using by user {} for drug {}", authentication.userId, createDTO.drugId)
        val using = treatmentPlanService.create(authentication.userId, createDTO.drugId, createDTO.plannedAmount)
        return using.toDto()
    }

    @PutMapping("/drug/{drugId}")
    @Operation(summary = "Update treatment plan", description = "Updates the planned amount for a drug.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Treatment plan updated",
                content = [Content(schema = Schema(implementation = UsingDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid input", content = [Content()]),
            ApiResponse(responseCode = "404", description = "Treatment plan not found", content = [Content()])
        ]
    )
    fun updateUsing(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable drugId: UUID,
        @SwaggerRequestBody(description = "Updated treatment plan")
        @Valid @RequestBody updateDTO: UsingUpdateDTO
    ): UsingDTO {
        logger.debug("PUT /v1/using/drug/{} by user {}", drugId, authentication.userId)
        val using = treatmentPlanService.patch(authentication.userId, drugId, updateDTO.plannedAmount)
        return using.toDto()
    }

    @PostMapping("/drug/{drugId}/intake")
    @Operation(summary = "Record intake", description = "Registers a drug intake and updates the planned amount.")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Intake recorded",
                content = [Content(schema = Schema(implementation = UsingDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid intake amount", content = [Content()]),
            ApiResponse(responseCode = "404", description = "Treatment plan not found", content = [Content()])
        ]
    )
    fun recordRegularUsing(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable drugId: UUID,
        @SwaggerRequestBody(description = "Intake details")
        @Valid @RequestBody intakeRequest: IntakeRequest
    ): UsingDTO? {
        logger.debug(
            "POST /v1/using/drug/{}/intake by user {}, quantity: {}",
            drugId, authentication.userId, intakeRequest.quantityConsumed
        )
        return intakeService.record(
            authentication.userId,
            drugId,
            intakeRequest.quantityConsumed,
            intakeRequest.intakeId
        ).plan?.toDto()
    }

    @DeleteMapping("/drug/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete treatment plan", description = "Deletes a planned usage for a drug.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Treatment plan deleted"),
            ApiResponse(responseCode = "404", description = "Treatment plan not found", content = [Content()])
        ]
    )
    fun deleteTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable drugId: UUID
    ) {
        logger.debug("DELETE /v1/using/drug/{} by user {}", drugId, authentication.userId)
        treatmentPlanService.delete(authentication.userId, drugId)
    }
}
