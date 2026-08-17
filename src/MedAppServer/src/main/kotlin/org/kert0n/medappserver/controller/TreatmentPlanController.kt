package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.IntakeResultDTO
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanDTO
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.services.models.userId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

/**
 * План лечения адресуется препаратом: у пользователя не может быть двух планов на один
 * препарат, поэтому пары «пользователь + препарат» достаточно, а собственный идентификатор
 * плана был бы лишней сущностью.
 */
@RestController
@RequestMapping("/v1/treatment-plans")
@Tag(name = "Treatment plans", description = "How much of a drug the user reserved for themselves")
class TreatmentPlanController(
    private val treatmentPlanService: TreatmentPlanService,
    // Планы читаются своим сервисом, а меняются через корень агрегата: остаток препарата и
    // все планы на него видны только там.
    private val drugService: DrugService
) {

    private val logger = LoggerFactory.getLogger(TreatmentPlanController::class.java)

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Plans returned")
    fun listTreatmentPlans(authentication: Authentication): List<TreatmentPlanDTO> {
        logger.debug("GET /v1/treatment-plans by user {}", authentication.userId)
        return treatmentPlanService.plansOf(authentication.userId).map { it.toDto() }
    }

    @GetMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Plan found")
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    fun getTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID
    ): TreatmentPlanDTO {
        logger.debug("GET /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        return treatmentPlanService.requirePlan(authentication.userId, drugId).toDto()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Plan created")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the available stock", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Plan for this drug already exists", content = [Content()])
    fun createTreatmentPlan(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Plan to create")
        @Valid @RequestBody request: TreatmentPlanCreateRequest
    ): TreatmentPlanDTO {
        logger.debug("POST /v1/treatment-plans by user {} for drug {}", authentication.userId, request.drugId)
        drugService.createPlan(authentication.userId, request.drugId, request.plannedAmount)
        return treatmentPlanService.requirePlan(authentication.userId, request.drugId).toDto()
    }

    @PatchMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Plan updated")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the available stock", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    fun patchTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @SwaggerRequestBody(description = "New planned amount")
        @Valid @RequestBody request: TreatmentPlanPatchRequest
    ): TreatmentPlanDTO {
        logger.debug("PATCH /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        drugService.changePlan(authentication.userId, drugId, request.plannedAmount)
        return treatmentPlanService.requirePlan(authentication.userId, drugId).toDto()
    }

    @DeleteMapping("/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Plan deleted")
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    fun deleteTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID
    ) {
        logger.debug("DELETE /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        drugService.cancelPlan(authentication.userId, drugId)
    }
}

@RestController
@RequestMapping("/v1/intakes")
@Tag(name = "Intakes", description = "Recorded intakes of planned drugs")
class IntakeController {

    private val logger = LoggerFactory.getLogger(IntakeController::class.java)

    /**
     * Форма опубликована, механизм — нет.
     *
     * `PUT` с идентификатором от клиента обещает идемпотентность: приём сам по себе не
     * идемпотентен, два вызова спишут вдвое больше, а повтор при обрыве связи для мобильного
     * клиента — обычное дело. Обещание держится на хранилище результатов и на защите от
     * одновременных команд, а это работа про конкурентность: она делается вместе с
     * версионностью агрегатов.
     *
     * До тех пор эндпойнт честно отвечает `501`. Включить его наполовину значило бы
     * пообещать в контракте свойство, которого нет.
     */
    @PutMapping("/{intakeId}")
    @ApiResponse(responseCode = "501", description = "Intake is not enabled yet", content = [Content()])
    fun recordIntake(
        authentication: Authentication,
        @Parameter(description = "Client-generated intake identifier") @PathVariable intakeId: UUID,
        @SwaggerRequestBody(description = "Intake details")
        @Valid @RequestBody request: IntakeRequest
    ): IntakeResultDTO {
        logger.debug("PUT /v1/intakes/{} by user {} — not implemented yet", intakeId, authentication.userId)
        throw ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Intake is not enabled yet")
    }
}
