package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.VocabularyEntryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.OpenApiConfiguration
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.application.CatalogueApplicationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1")
@Tag(name = "Drugs", description = "Drugs stored in medicine kits")
class DrugController(private val drugs: DrugApplicationService) {

    private val logger = LoggerFactory.getLogger(DrugController::class.java)

    @GetMapping("/drugs/{drugId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Get a drug",
        description = "Returns a drug the caller has access to."
    )
    @ApiResponse(responseCode = "200", description = "Drug found")
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    fun getDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: Uuid
    ): DrugSnapshotDTO {
        logger.debug("GET /v1/drugs/{} by user {}", drugId, authentication.userId)
        return drugs.read(drugId, authentication.userId)
    }

    /** Аптечка задаётся путём: упаковка не существует сама по себе, она всегда в аптечке. */
    @PostMapping("/med-kits/{medKitId}/drugs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Add a drug to a medicine kit",
        description = "Creates a drug in the given kit."
    )
    @ApiResponse(responseCode = "201", description = "Drug created")
    @ApiResponse(responseCode = "400", description = "Invalid request", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Medicine kit is not accessible", content = [Content()])
    fun createDrug(
        authentication: Authentication,
        @Parameter(description = "Medicine kit identifier") @PathVariable medKitId: Uuid,
        @SwaggerRequestBody(description = "Drug to create")
        @Valid @RequestBody request: DrugCreateRequest
    ): DrugSnapshotDTO {
        logger.debug("POST /v1/med-kits/{}/drugs by user {}", medKitId, authentication.userId)
        return drugs.createInMedKit(medKitId, request, authentication.userId)
    }

    /** PATCH, а не PUT: тело описывает изменение части полей, а не препарат целиком. */
    @PatchMapping("/drugs/{drugId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Update a package",
        description = "Changes the given fields; absent fields are left as they are. Quantity here is a correction " +
            "of the record — you recounted the pack and saw a different number — not a refill, and it leaves " +
            "reservations alone."
    )
    @ApiResponse(responseCode = "200", description = "Drug updated")
    @ApiResponse(responseCode = "400", description = "Invalid request", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    fun patchDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: Uuid,
        @SwaggerRequestBody(description = "Fields to change")
        @Valid @RequestBody request: DrugPatchRequest
    ): DrugSnapshotDTO {
        logger.debug("PATCH /v1/drugs/{} by user {}", drugId, authentication.userId)
        return drugs.update(drugId, request, authentication.userId)
    }

    @DeleteMapping("/drugs/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Delete a drug",
        description = "Destroys the package and every reservation placed on it."
    )
    @ApiResponse(responseCode = "204", description = "Drug deleted")
    @ApiResponse(responseCode = "404", description = "Drug does not exist or is not accessible", content = [Content()])
    fun deleteDrug(
        authentication: Authentication,
        @Parameter(description = "Drug identifier") @PathVariable drugId: Uuid,
        @Parameter(description = "Version the command acts on; absent means 428")
        @RequestParam(required = false) version: Long?
    ) {
        logger.debug("DELETE /v1/drugs/{} by user {}", drugId, authentication.userId)
        drugs.delete(drugId, version, authentication.userId)
    }

    /**
     * Приём — запись о съеденном, поэтому POST в подчинённый ресурс, а не PUT в упаковку.
     *
     * Единственный способ уменьшить пачку; бронь её владелец правит отдельно. Ответа нет, когда
     * приём опустошил пачку и та уничтожена.
     */
    @PostMapping("/drugs/{drugId}/intakes")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Record an intake",
        description = "Takes the given amount out of the package — the only way its contents decrease. There is no " +
            "distinction between a planned intake and an emergency one: what was taken reduces the package, and the " +
            "reservation is the owner's to adjust. Taking more than the package holds is refused: a package cannot " +
            "be refilled, so a second pack is a second package. Returns no body when the package ran out and was " +
            "destroyed."
    )
    @ApiResponse(responseCode = "200", description = "Package reduced")
    @ApiResponse(responseCode = "204", description = "Package ran out and was destroyed", content = [Content()])
    @ApiResponse(responseCode = "400", description = "Amount exceeds what is left in the package", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Package does not exist or is not accessible", content = [Content()])
    fun recordIntake(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: Uuid,
        @SwaggerRequestBody(description = "Amount taken")
        @Valid @RequestBody request: IntakeRequest
    ): DrugSnapshotDTO? {
        logger.debug("POST /v1/drugs/{}/intakes by user {}", drugId, authentication.userId)
        // null означает, что пачка кончилась и уничтожена этим списанием.
        return drugs.recordIntake(drugId, request.quantity, request.version, authentication.userId)
    }

    /**
     * Синхронизация офлайн-изменений одной упаковки.
     *
     * PUT с придуманным клиентом идентификатором: повтор того же запроса — то же состояние, а
     * не второе списание. Версии едут в теле, потому что запрос меняет два состояния сразу и
     * разложить их по одному месту нельзя.
     */
    @PutMapping("/drugs/{drugId}/sync/{syncId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Apply offline changes to a package",
        description = "Applies the consumed amount and the new claim in one transaction. " +
            "Repeating the same request under the same identifier changes nothing; the same " +
            "identifier with different content is a conflict."
    )
    @ApiResponse(responseCode = "200", description = "Changes applied")
    @ApiResponse(responseCode = "204", description = "Package emptied by this request and destroyed")
    @ApiResponse(responseCode = "404", description = "Package does not exist or is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Stated version is not current, or the identifier was used for a different request", content = [Content()])
    fun synchronise(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: Uuid,
        @Parameter(description = "Client-invented identifier of this synchronisation") @PathVariable syncId: Uuid,
        @SwaggerRequestBody(description = "Offline changes")
        @Valid @RequestBody request: DrugSyncRequest
    ): DrugSnapshotDTO? {
        logger.debug("PUT /v1/drugs/{}/sync/{} by user {}", drugId, syncId, authentication.userId)
        return drugs.synchronise(drugId, syncId, request, authentication.userId)
    }

    /**
     * Перенос выражен как размещение препарата в целевой аптечке: PUT в тот путь, по
     * которому препарат окажется. Отдельного тела не нужно — обе стороны в пути.
     */
    @PutMapping("/med-kits/{targetMedKitId}/drugs/{drugId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Move a drug to another medicine kit",
        description = "Transfers the drug between kits."
    )
    @ApiResponse(responseCode = "200", description = "Drug moved")
    @ApiResponse(responseCode = "404", description = "Drug or target kit is not accessible", content = [Content()])
    fun moveDrug(
        authentication: Authentication,
        @Parameter(description = "Target medicine kit identifier") @PathVariable targetMedKitId: Uuid,
        @Parameter(description = "Drug identifier") @PathVariable drugId: Uuid,
        @Parameter(description = "Version the command acts on; absent means 428")
        @RequestParam(required = false) version: Long?
    ): DrugSnapshotDTO {
        logger.debug("PUT /v1/med-kits/{}/drugs/{} by user {}", targetMedKitId, drugId, authentication.userId)
        return drugs.moveToMedKit(drugId, targetMedKitId, version, authentication.userId)
    }
}

@RestController
@RequestMapping("/v1/drug-templates")
@Tag(name = "Drug catalogue", description = "Reference catalogue used when adding a drug")
class DrugTemplateController(
    private val catalogue: CatalogueApplicationService
) {

    private val logger = LoggerFactory.getLogger(DrugTemplateController::class.java)

    @GetMapping
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Search the catalogue",
        description = "Searches by name, Latin name, active substance and manufacturer."
    )
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
        return catalogue.search(query, limit)
    }

    @GetMapping("/{templateId}")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "Get a catalogue entry",
        description = "Returns a single catalogue entry."
    )
    @ApiResponse(responseCode = "200", description = "Entry found")
    @ApiResponse(responseCode = "404", description = "Entry does not exist", content = [Content()])
    fun getDrugTemplate(
        authentication: Authentication,
        @Parameter(description = "Template identifier") @PathVariable templateId: Uuid
    ): DrugTemplateDTO {
        logger.debug("GET /v1/drug-templates/{} by user {}", templateId, authentication.userId)
        return catalogue.template(templateId)
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
    private val catalogue: CatalogueApplicationService
) {

    private val logger = LoggerFactory.getLogger(VocabularyController::class.java)

    @GetMapping("/quantity-units")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "List quantity units",
        description = "Shared vocabulary of quantity units. A drug references a unit by identifier, so this list is " +
            "where the identifier comes from."
    )
    @ApiResponse(responseCode = "200", description = "Units returned")
    fun listQuantityUnits(authentication: Authentication): List<VocabularyEntryDTO> {
        logger.debug("GET /v1/quantity-units by user {}", authentication.userId)
        return catalogue.quantityUnits()
    }

    @GetMapping("/form-types")
    @Operation(
        security = [SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)],
        summary = "List dosage forms",
        description = "Shared vocabulary of dosage forms, used the same way as quantity units."
    )
    @ApiResponse(responseCode = "200", description = "Dosage forms returned")
    fun listFormTypes(authentication: Authentication): List<VocabularyEntryDTO> {
        logger.debug("GET /v1/form-types by user {}", authentication.userId)
        return catalogue.formTypes()
    }
}
