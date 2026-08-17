package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.kert0n.medappserver.api.ConsumptionRequest
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.VocabularyEntryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.CatalogueService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1")
@Tag(name = "Drugs", description = "Drugs stored in medicine kits")
class DrugController(
    private val drugService: DrugService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator
) {

    private val logger = LoggerFactory.getLogger(DrugController::class.java)

    @GetMapping("/drugs/{drugId}")
    @ApiResponse(responseCode = "200", description = "Drug found")
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    fun getDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID
    ): ResponseEntity<DrugDTO> {
        logger.debug("GET /v1/drugs/{} by user {}", drugId, authentication.userId)
        val drug = drugService.require(drugId, authentication.userId)
        return Preconditions.withEtag(drug.version, drug.toDto())
    }

    /**
     * Аптечка задаётся путём: препарат не существует сам по себе, он всегда лежит в аптечке.
     * Раньше она приходила полем тела, и запрос выглядел так, будто препарат можно создать
     * без неё.
     */
    /**
     * Предусловия здесь нет: создание ничего не перезаписывает.
     *
     * Версия аптечки тоже не требуется — препарат в неё добавляется, состав участников при
     * этом не меняется, и потерянного обновления в этой команде не бывает. Доступ проверяется
     * внутри транзакции, а не заголовком.
     */
    @PostMapping("/med-kits/{medKitId}/drugs")
    @ApiResponse(responseCode = "201", description = "Drug created")
    @ApiResponse(responseCode = "400", description = "Invalid request", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Medicine kit is not accessible", content = [Content()])
    fun createDrug(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: UUID,
        @SwaggerRequestBody(description = "Drug to create")
        @Valid @RequestBody request: DrugCreateRequest
    ): ResponseEntity<DrugDTO> {
        logger.debug("POST /v1/med-kits/{}/drugs by user {}", medKitId, authentication.userId)
        val created = medKitDrugOrchestrator.createDrugInMedKit(medKitId, request, authentication.userId)
        val drug = drugService.require(created.id, authentication.userId)
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Preconditions.etag(drug.version))
            .body(drug.toDto())
    }

    /** PATCH, а не PUT: тело описывает изменение части полей, а не препарат целиком. */
    @PatchMapping("/drugs/{drugId}")
    @ApiResponse(responseCode = "200", description = "Drug updated")
    @ApiResponse(responseCode = "400", description = "Invalid request or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun patchDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Fields to change")
        @Valid @RequestBody request: DrugPatchRequest
    ): ResponseEntity<DrugDTO> {
        logger.debug("PATCH /v1/drugs/{} by user {}", drugId, authentication.userId)
        val version = Preconditions.requiredVersion(ifMatch)
        val updated = drugService.update(drugId, request, authentication.userId, version)
        return Preconditions.withEtag(updated.version, updated.toDto())
    }

    @DeleteMapping("/drugs/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Drug deleted")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/drugs/{} by user {}", drugId, authentication.userId)
        drugService.delete(drugId, authentication.userId, Preconditions.requiredVersion(ifMatch))
    }

    /**
     * Расход — это создание записи о нём, поэтому POST в подчинённый ресурс, а не PUT в
     * препарат. Ответ отсутствует, когда расход исчерпал препарат и тот удалён.
     */
    @PostMapping("/drugs/{drugId}/consumptions")
    @ApiResponse(responseCode = "200", description = "Stock reduced; empty body when the drug ran out")
    @ApiResponse(responseCode = "400", description = "Amount exceeds the stock or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun consumeDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Amount consumed")
        @Valid @RequestBody request: ConsumptionRequest
    ): ResponseEntity<DrugDTO> {
        logger.debug("POST /v1/drugs/{}/consumptions by user {}", drugId, authentication.userId)
        val version = Preconditions.requiredVersion(ifMatch)
        // null означает, что препарат кончился и удалён этим списанием: тела нет, и тега тоже
        // — представления, к которому он относился бы, больше не существует.
        val left = drugService.consume(drugId, request.quantity, authentication.userId, version)
            ?: return ResponseEntity.ok().build()
        return Preconditions.withEtag(left.version, left.toDto())
    }

    /**
     * Перенос выражен как размещение препарата в целевой аптечке: PUT в тот путь, по
     * которому препарат окажется. Отдельного тела не нужно — обе стороны в пути.
     *
     * Предъявляется версия препарата, а не целевой аптечки: переезжает препарат, аптечка лишь
     * называет, кто его после этого увидит.
     */
    @PutMapping("/med-kits/{targetMedKitId}/drugs/{drugId}")
    @ApiResponse(responseCode = "200", description = "Drug moved")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug or target kit is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Drug was changed by someone else", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun moveDrug(
        authentication: Authentication,
        @Parameter(description = "Target medicine kit identifier") @PathVariable targetMedKitId: UUID,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(description = IF_MATCH_DESCRIPTION)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?
    ): ResponseEntity<DrugDTO> {
        logger.debug("PUT /v1/med-kits/{}/drugs/{} by user {}", targetMedKitId, drugId, authentication.userId)
        val version = Preconditions.requiredVersion(ifMatch)
        val moved = medKitDrugOrchestrator.moveDrug(drugId, targetMedKitId, authentication.userId, version)
        return Preconditions.withEtag(moved.version, moved.toDto())
    }
}

/** Один текст на все команды: он один и тот же, а повторять его в каждом параметре незачем. */
const val IF_MATCH_DESCRIPTION: String =
    "Strong entity tag of the aggregate being changed, as returned in `ETag` — for example `\"3\"`"

@RestController
@RequestMapping("/v1/drug-templates")
@Tag(name = "Drug catalogue", description = "Reference catalogue used when adding a drug")
class DrugTemplateController(
    private val catalogueService: CatalogueService
) {

    private val logger = LoggerFactory.getLogger(DrugTemplateController::class.java)

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Matches found")
    @ApiResponse(responseCode = "400", description = "Invalid query or limit", content = [Content()])
    fun searchDrugTemplates(
        authentication: Authentication,
        @Parameter(description = "Search query")
        @RequestParam @Size(min = 1, max = 200) query: String,
        // Границы указаны дважды намеренно. Проверяют их @Min/@Max, но springdoc не
        // переносит их в схему параметра запроса, и без явной схемы опубликованный контракт
        // умалчивал бы о пределе. Держать в согласии.
        @Parameter(
            description = "Maximum number of results",
            schema = Schema(type = "integer", format = "int32", minimum = "1", maximum = "50")
        )
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int
    ): List<DrugTemplateDTO> {
        logger.debug("GET /v1/drug-templates by user {}", authentication.userId)
        return catalogueService.fuzzySearch(query, limit).map { it.toDto() }
    }

    @GetMapping("/{templateId}")
    @ApiResponse(responseCode = "200", description = "Entry found")
    @ApiResponse(responseCode = "404", description = "Entry does not exist", content = [Content()])
    fun getDrugTemplate(
        authentication: Authentication,
        @Parameter(description = "Template identifier") @PathVariable templateId: UUID
    ): DrugTemplateDTO {
        logger.debug("GET /v1/drug-templates/{} by user {}", templateId, authentication.userId)
        return catalogueService.find(templateId)?.toDto()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug template not found")
    }
}

/**
 * Общие словари: единицы измерения и формы выпуска.
 *
 * Препарат ссылается на них идентификатором, поэтому клиенту нужен список — иначе взять
 * идентификатор неоткуда. Словари одни и те же и для каталога, и для заведённого руками
 * препарата.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Vocabularies", description = "Shared quantity units and dosage forms")
class VocabularyController(
    private val catalogueService: CatalogueService
) {

    private val logger = LoggerFactory.getLogger(VocabularyController::class.java)

    @GetMapping("/quantity-units")
    @ApiResponse(responseCode = "200", description = "Units returned")
    fun listQuantityUnits(authentication: Authentication): List<VocabularyEntryDTO> {
        logger.debug("GET /v1/quantity-units by user {}", authentication.userId)
        return catalogueService.quantityUnits().map { it.toDto() }
    }

    @GetMapping("/form-types")
    @ApiResponse(responseCode = "200", description = "Dosage forms returned")
    fun listFormTypes(authentication: Authentication): List<VocabularyEntryDTO> {
        logger.debug("GET /v1/form-types by user {}", authentication.userId)
        return catalogueService.formTypes().map { it.toDto() }
    }
}
