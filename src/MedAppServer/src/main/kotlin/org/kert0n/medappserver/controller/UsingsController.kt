package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.IntakeService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/using")
@Tag(name = "Treatment Plans", description = "Endpoints for treatment plans and intake tracking")
class UsingsController(
    private val usingService: UsingService,
    private val intakeService: IntakeService,
    private val logger: Logger = LoggerFactory.getLogger(UsingsController::class.java)
) {


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
        val usings = usingService.findAllByUser(authentication.userId)
        return usings.map { usingService.toUsingDTO(it) }
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
    ): UsingDTO? {
        logger.debug("GET /v1/using/drug/{} by user {}", drugId, authentication.userId)
        // Нетбросающая выборка: отсутствие плана — пустой ответ, а не 404. Проект не ведёт
        // tombstone'ов, поэтому старый клиент законно приходит за уже удалённым планом, и
        // по 404 он не отличит «плана нет» от «эндпоинт сломался».
        val using = usingService.findByUserAndDrugOrNull(authentication.userId, drugId)
        return using?.let { usingService.toUsingDTO(it) }
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
        val using = usingService.createTreatmentPlan(authentication.userId, createDTO)
        return usingService.toUsingDTO(using)
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
        val using = usingService.updateTreatmentPlan(authentication.userId, drugId, updateDTO)
        return usingService.toUsingDTO(using)
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
        ).plan
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
        usingService.deleteTreatmentPlan(authentication.userId, drugId)
    }
}

@Schema(description = "Intake request")
data class IntakeRequest(
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Amount consumed", example = "1.0", minimum = "0")
    val quantityConsumed: BigDecimal,

    @NotNull
    @Schema(
        description = "Client-generated identifier of this intake event. Retrying with the same " +
            "value returns the first result instead of applying the intake twice.",
        required = true
    )
    val intakeId: UUID
)

@Schema(description = "Treatment plan information")
data class UsingDTO(
    @Schema(description = "User identifier")
    val userId: UUID,
    @Schema(description = "Drug identifier")
    val drugId: UUID,
    @Schema(description = "Planned total amount for the course")
    val plannedAmount: BigDecimal
)

@Schema(description = "Create treatment plan request")
data class UsingCreateDTO(
    @NotNull
    @Schema(description = "Drug identifier")
    val drugId: UUID,

    @NotNull
    @DecimalMin("0.0")
    @Schema(description = "Planned amount", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)

@Schema(description = "Update treatment plan request")
data class UsingUpdateDTO(
    @NotNull
    @DecimalMin("0.0")
    @Schema(description = "Planned amount", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)
