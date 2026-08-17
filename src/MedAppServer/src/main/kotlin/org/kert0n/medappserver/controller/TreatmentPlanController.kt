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
import org.kert0n.medappserver.api.planDtoOf
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.IntakeService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.services.models.userId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
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
    ): ResponseEntity<TreatmentPlanDTO> {
        logger.debug("GET /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        val entry = treatmentPlanService.requirePlan(authentication.userId, drugId)
        // Тег принадлежит препарату: план — часть его агрегата, и меняются они вместе.
        return Preconditions.withEtag(entry.drugVersion, entry.toDto())
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Plan created")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the available stock or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Plan already exists or the drug was changed", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun createTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Plan to create")
        @Valid @RequestBody request: TreatmentPlanCreateRequest
    ): ResponseEntity<TreatmentPlanDTO> {
        logger.debug("POST /v1/treatment-plans by user {} for drug {}", authentication.userId, request.drugId)
        val version = Preconditions.requiredVersion(ifMatch)
        val drug = drugService.createPlan(authentication.userId, request.drugId, request.plannedAmount, version)
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Preconditions.etag(drug.version))
            .body(drug.planDtoOf(authentication.userId))
    }

    @PatchMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Plan updated")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the available stock or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun patchTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "New planned amount")
        @Valid @RequestBody request: TreatmentPlanPatchRequest
    ): ResponseEntity<TreatmentPlanDTO> {
        logger.debug("PATCH /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        val version = Preconditions.requiredVersion(ifMatch)
        val drug = drugService.changePlan(authentication.userId, drugId, request.plannedAmount, version)
        return Preconditions.withEtag(drug.version, drug.planDtoOf(authentication.userId))
    }

    @DeleteMapping("/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Plan deleted")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteTreatmentPlan(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/treatment-plans/{} by user {}", drugId, authentication.userId)
        drugService.cancelPlan(authentication.userId, drugId, Preconditions.requiredVersion(ifMatch))
    }
}

@RestController
@RequestMapping("/v1/intakes")
@Tag(name = "Intakes", description = "Recorded intakes of planned drugs")
class IntakeController(private val intakeService: IntakeService) {

    private val logger = LoggerFactory.getLogger(IntakeController::class.java)

    /**
     * Приём по плану лечения.
     *
     * Форма была опубликована раньше механизма и до этого момента отвечала `501`: `PUT` с
     * идентификатором от клиента обещает идемпотентность, а её нечем было держать. Теперь
     * обещание выполняется — повтор с тем же идентификатором возвращает первый результат, тот
     * же идентификатор с другим содержимым отвергается.
     *
     * Предъявляется версия препарата: списывается и план, и остаток, а принадлежат они ему.
     */
    @PutMapping("/{intakeId}")
    @ApiResponse(responseCode = "200", description = "Intake applied, or replayed from an earlier identical request")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the plan or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No plan for this drug", content = [Content()])
    @ApiResponse(
        responseCode = "409",
        description = "Drug was changed by someone else, or this identifier was used for a different intake",
        content = [Content()]
    )
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun recordIntake(
        authentication: Authentication,
        @Parameter(description = "Client-generated intake identifier") @PathVariable intakeId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Intake details")
        @Valid @RequestBody request: IntakeRequest
    ): ResponseEntity<IntakeResultDTO> {
        logger.debug("PUT /v1/intakes/{} by user {}", intakeId, authentication.userId)
        val version = Preconditions.requiredVersion(ifMatch)
        val outcome = intakeService.record(
            intakeId = intakeId,
            userId = authentication.userId,
            drugId = request.drugId,
            quantityConsumed = request.quantityConsumed,
            expectedVersion = version
        )

        val body = IntakeResultDTO(
            treatmentPlan = outcome.drug?.let { drug ->
                outcome.plan?.let { TreatmentPlanDTO(drug.id, it.plannedAmount.amount, drug.version) }
            },
            drug = outcome.drug?.toDto()
        )
        // Препарата не стало — тега нет: представления, к которому он относился бы, больше не
        // существует, а старая версия отправила бы клиента предъявлять её удалённой строке.
        val drug = outcome.drug ?: return ResponseEntity.ok(body)
        return Preconditions.withEtag(drug.version, body)
    }
}
