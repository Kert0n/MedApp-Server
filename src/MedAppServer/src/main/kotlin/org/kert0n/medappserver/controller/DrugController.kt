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
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.SyncRequest
import org.kert0n.medappserver.api.SyncResultDTO
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.VocabularyEntryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.CatalogueService
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.orchestrators.DrugSyncOrchestrator
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.slf4j.LoggerFactory
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
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator,
    private val drugSyncOrchestrator: DrugSyncOrchestrator,
    private val preconditions: Preconditions
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
        return medKitDrugOrchestrator.drug(drugId, authentication.userId).withEtag()
    }

    /** Аптечка задаётся путём: упаковка не существует сама по себе, она всегда в аптечке. */
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
        return medKitDrugOrchestrator.drug(created.id, authentication.userId).createdWithEtag()
    }

    /** PATCH, а не PUT: тело описывает изменение части полей, а не препарат целиком. */
    @PatchMapping("/drugs/{drugId}")
    @ApiResponse(responseCode = "200", description = "Drug updated")
    @ApiResponse(responseCode = "400", description = "Invalid request or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Drug has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun patchDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Fields to change")
        @Valid @RequestBody request: DrugPatchRequest
    ): ResponseEntity<DrugDTO> {
        logger.debug("PATCH /v1/drugs/{} by user {}", drugId, authentication.userId)
        drugService.update(drugId, request, authentication.userId, preconditions.requiredMatch(ifMatch))
        return medKitDrugOrchestrator.drug(drugId, authentication.userId).withEtag()
    }

    @DeleteMapping("/drugs/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Drug deleted")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Drug has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/drugs/{} by user {}", drugId, authentication.userId)
        drugService.delete(drugId, authentication.userId, preconditions.requiredMatch(ifMatch))
    }

    /**
     * Приём — запись о съеденном, поэтому POST в подчинённый ресурс, а не PUT в упаковку.
     *
     * Единственный способ уменьшить пачку; бронь её владелец правит отдельно. Ответа нет, когда
     * приём опустошил пачку и та уничтожена.
     */
    @PostMapping("/drugs/{drugId}/intakes")
    @ApiResponse(responseCode = "200", description = "Package reduced")
    @ApiResponse(responseCode = "204", description = "Package ran out and was destroyed", content = [Content()])
    @ApiResponse(responseCode = "400", description = "Amount exceeds the package, or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Package does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Package has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun recordIntake(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "Amount taken")
        @Valid @RequestBody request: IntakeRequest
    ): ResponseEntity<DrugDTO> {
        logger.debug("POST /v1/drugs/{}/intakes by user {}", drugId, authentication.userId)
        val expected = preconditions.requiredMatch(ifMatch)
        // Пустая пачка уничтожена этим приёмом: тега у того, чего нет, тоже нет.
        drugService.consume(drugId, request.quantity, authentication.userId, expected)
            ?: return noContentWithoutEtag()
        return medKitDrugOrchestrator.drug(drugId, authentication.userId).withEtag()
    }

    /**
     * Пакетная синхронизация одной пачки: приём и бронь атомарно.
     *
     * `PUT` в путь с идентификатором от клиента, а не `POST`: повторный запрос обязан давать
     * тот же результат, а не второе списание, и идентификатор в пути — это ровно обещание
     * «повтори сколько угодно раз». Предусловия едут в теле: ресурса два, а `If-Match` один.
     */
    @PutMapping("/drugs/{drugId}/sync/{syncId}")
    @ApiResponse(responseCode = "200", description = "Applied; both resources returned")
    @ApiResponse(responseCode = "204", description = "Package ran out and was destroyed", content = [Content()])
    @ApiResponse(responseCode = "400", description = "Amount exceeds the package, or the request asks for nothing", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Package does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "A version is stale, or the sync id was used for a different request", content = [Content()])
    fun synchroniseDrug(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID,
        @Parameter(description = "Client-chosen identifier of this synchronisation") @PathVariable syncId: UUID,
        @SwaggerRequestBody(description = "Cumulative changes")
        @Valid @RequestBody request: SyncRequest
    ): ResponseEntity<SyncResultDTO> {
        logger.debug("PUT /v1/drugs/{}/sync/{} by user {}", drugId, syncId, authentication.userId)
        val result = drugSyncOrchestrator.synchronise(syncId, drugId, request, authentication.userId)
            ?: return noContentWithoutEtag()
        return ResponseEntity.ok(result)
    }

    /**
     * Перенос выражен как размещение препарата в целевой аптечке: PUT в тот путь, по
     * которому препарат окажется. Отдельного тела не нужно — обе стороны в пути.
     */
    @PutMapping("/med-kits/{targetMedKitId}/drugs/{drugId}")
    @ApiResponse(responseCode = "200", description = "Drug moved")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug or target kit is not accessible", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Drug has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun moveDrug(
        authentication: Authentication,
        @Parameter(description = "Target medicine kit identifier") @PathVariable targetMedKitId: UUID,
        @Parameter(description = "Drug identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?
    ): ResponseEntity<DrugDTO> {
        logger.debug("PUT /v1/med-kits/{}/drugs/{} by user {}", targetMedKitId, drugId, authentication.userId)
        // Предъявляется версия упаковки: переезжает она, а аптечка только называет состав.
        medKitDrugOrchestrator.moveDrug(
            drugId, targetMedKitId, authentication.userId, preconditions.requiredMatch(ifMatch)
        )
        return medKitDrugOrchestrator.drug(drugId, authentication.userId).withEtag()
    }
}

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
        // Границы указаны дважды намеренно: проверяют их @Min/@Max, но springdoc не переносит
        // их в схему параметра, и контракт умалчивал бы о пределе. Держать в согласии.
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
 * Упаковка ссылается на них идентификатором, поэтому клиенту нужен список. Словари одни и те же
 * и для каталога, и для заведённой руками пачки.
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
