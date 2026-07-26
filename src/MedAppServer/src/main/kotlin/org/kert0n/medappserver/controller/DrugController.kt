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
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.models.userId
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/drug")
@Tag(name = "Drug Management", description = "APIs for managing drugs in medicine kits")
class DrugController(
    private val drugService: DrugService,
    private val vidalDrugService: VidalDrugService,
    private val medKitDrugServices: MedKitDrugServices
) {

    private val logger = LoggerFactory.getLogger(DrugController::class.java)

    @GetMapping("/{id}")
    @Operation(summary = "Get drug by ID", description = "Retrieves a drug by its ID if the user has access")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Drug found"),
            ApiResponse(responseCode = "404", description = "Drug not found or access denied", content = [Content()])
        ]
    )
    fun getDrug(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID
    ): DrugDTO {
        logger.debug("GET /v1/drug/{} by user {}", id, authentication.userId)
        val drug = drugService.findByIdForUser(id, authentication.userId)
        return drugService.toDrugDTO(drug)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new drug", description = "Creates a new drug in a medicine kit")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Drug created successfully"),
            ApiResponse(responseCode = "400", description = "Invalid input", content = [Content()]),
            ApiResponse(
                responseCode = "403",
                description = "User does not have access to the medicine kit",
                content = [Content()]
            )
        ]
    )
    fun createDrug(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Drug details to create")
        @Valid @RequestBody drugDTO: DrugCreateDTO
    ): DrugDTO {
        logger.debug("POST /v1/drug by user {}: {}", authentication.userId, drugDTO.name)
        val drug = medKitDrugServices.createDrugInMedkit(drugDTO, authentication.userId)
        return drugService.toDrugDTO(drug)
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a drug", description = "Updates an existing drug")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Drug updated successfully"),
            ApiResponse(responseCode = "400", description = "Invalid input", content = [Content()]),
            ApiResponse(responseCode = "404", description = "Drug not found", content = [Content()])
        ]
    )
    fun updateDrug(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID,
        @SwaggerRequestBody(description = "Drug update details")
        @Valid @RequestBody updateDTO: DrugUpdateDTO
    ): DrugDTO {
        logger.debug("PUT /v1/drug/{} by user {}", id, authentication.userId)
        val drug = drugService.update(id, updateDTO, authentication.userId)
        return drugService.toDrugDTO(drug)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a drug", description = "Deletes a drug from the medicine kit")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Drug deleted successfully"),
            ApiResponse(responseCode = "404", description = "Drug not found", content = [Content()])
        ]
    )
    fun deleteDrug(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID
    ) {
        logger.debug("DELETE /v1/drug/{} by user {}", id, authentication.userId)
        drugService.delete(id, authentication.userId)
    }

    @GetMapping("/quantity/{id}")
    @Operation(summary = "Get drug quantity info", description = "Returns actual, planned, and available quantities")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Quantity info retrieved",
                content = [Content(schema = Schema(implementation = QuantityInfo::class))]
            ),
            ApiResponse(responseCode = "404", description = "Drug not found", content = [Content()])
        ]
    )
    fun getDrugQuantityInfo(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID
    ): QuantityInfo {
        logger.debug("GET /v1/drug/quantity/{} by user {}", id, authentication.userId)
        val drug = drugService.findByIdForUser(id, authentication.userId)
        return QuantityInfo(drug.quantity, drug.totalPlannedAmount, drug.quantity - drug.totalPlannedAmount)
    }

    @PutMapping("/consume/{id}")
    @Operation(summary = "Consume drug", description = "Reduces drug quantity by the consumed amount")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Drug consumed",
                content = [Content(schema = Schema(implementation = DrugDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid quantity", content = [Content()]),
            ApiResponse(responseCode = "404", description = "Drug not found", content = [Content()])
        ]
    )
    fun consumeDrug(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID,
        @SwaggerRequestBody(description = "Consumption details")
        @Valid @RequestBody consumeRequest: ConsumeRequest
    ): DrugDTO? {
        logger.debug(
            "PUT /v1/drug/consume/{} by user {}, quantity: {}",
            id,
            authentication.userId,
            consumeRequest.quantity
        )
        val drug = drugService.consumeDrug(id, consumeRequest.quantity, authentication.userId)
        return if (drug != null) drugService.toDrugDTO(drug) else null
    }

    @PutMapping("/move/{id}")
    @Operation(summary = "Move drug to another medicine kit", description = "Transfers a drug between medicine kits")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Drug moved",
                content = [Content(schema = Schema(implementation = DrugDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid target medkit", content = [Content()]),
            ApiResponse(responseCode = "404", description = "Drug or medkit not found", content = [Content()])
        ]
    )
    fun moveDrug(
        authentication: Authentication,
        @Parameter(description = "Drug ID") @PathVariable id: UUID,
        @SwaggerRequestBody(description = "Target medicine kit")
        @Valid @RequestBody moveRequest: MoveDrugRequest
    ): DrugDTO {
        logger.debug("PUT /v1/drug/move/{} to medkit {} by user {}", id, moveRequest.targetMedKitId, authentication.userId)
        val drug = medKitDrugServices.moveDrug(id, moveRequest.targetMedKitId, authentication.userId)
        return drugService.toDrugDTO(drug)
    }

    @GetMapping("/template/search")
    @Operation(summary = "Search drug templates", description = "Fuzzy search for drug templates in the database")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Templates found",
                content = [Content(schema = Schema(implementation = DrugTemplateDTO::class))]
            ),
            ApiResponse(responseCode = "400", description = "Invalid search term", content = [Content()])
        ]
    )
    fun searchDrugTemplates(
        authentication: Authentication,
        @Parameter(description = "Search term")
        @RequestParam @Size(min = 1, max = 200) searchTerm: String,
        // Unbounded before: limit=-1 reached Postgres as LIMIT -1 and failed with a 500,
        // limit=10000000 was an out-of-memory lever on an authenticated endpoint.
        //
        // The bounds are stated twice on purpose. @Min/@Max are what actually enforce them,
        // but springdoc does not render them into the schema for a query parameter (it does
        // render @Size, see searchTerm above), so without the explicit schema the published
        // contract would not mention the limit at all. Keep the two in step.
        @Parameter(
            description = "Maximum results",
            schema = Schema(type = "integer", format = "int32", minimum = "1", maximum = "50")
        )
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int
    ): List<DrugTemplateDTO> {
        logger.debug(
            "GET /v1/drug/template/search?searchTerm={}&limit={} by user {}",
            searchTerm,
            limit,
            authentication.userId
        )
        return vidalDrugService.fuzzySearchByName(searchTerm, limit).map { vd ->
            DrugTemplateDTO(
                id = vd.id,
                name = vd.name,
                formType = vd.formType?.name,
                category = vd.category,
                quantityUnit = vd.quantityUnit?.name,
                manufacturer = vd.manufacturer,
                country = vd.country,
                description = vd.description
            )
        }
    }

    @GetMapping("/template/{id}")
    @Operation(summary = "Get drug template by ID", description = "Retrieves a drug template from the database")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Template retrieved",
                content = [Content(schema = Schema(implementation = DrugTemplateDTO::class))]
            ),
            ApiResponse(responseCode = "404", description = "Template not found", content = [Content()])
        ]
    )
    fun getDrugTemplate(
        authentication: Authentication,
        @Parameter(description = "Template ID")
        @PathVariable id: UUID
    ): DrugTemplateDTO {
        logger.debug("GET /v1/drug/template/{} by user {}", id, authentication.userId)
        val vd = vidalDrugService.findById(id) ?: throw org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "Drug template not found"
        )
        return DrugTemplateDTO(
            id = vd.id,
            name = vd.name,
            formType = vd.formType?.name,
            category = vd.category,
            quantityUnit = vd.quantityUnit?.name,
            manufacturer = vd.manufacturer,
            country = vd.country,
            description = vd.description
        )
    }
}

@Schema(description = "Drug quantity information")
data class QuantityInfo(
    @Schema(description = "Actual quantity in stock")
    val actualQuantity: BigDecimal,
    @Schema(description = "Total planned quantity across all treatment plans")
    val plannedQuantity: BigDecimal,
    @Schema(description = "Available quantity (actual - planned)")
    val availableQuantity: BigDecimal
)

@Schema(description = "Request to consume a drug")
data class ConsumeRequest(
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Quantity to consume", example = "2.0", minimum = "0")
    val quantity: BigDecimal
)

@Schema(description = "Request to move a drug to another medicine kit")
data class MoveDrugRequest(
    @NotNull
    @Schema(description = "Target medicine kit ID")
    val targetMedKitId: UUID
)

@Schema(description = "Drug template from the database")
data class DrugTemplateDTO(
    @Schema(description = "Template ID")
    val id: UUID,
    @Schema(description = "Drug name")
    val name: String,
    @Schema(description = "Form type (e.g., tablet, syrup)")
    val formType: String?,
    @Schema(description = "Category")
    val category: String?,
    @Schema(description = "Quantity unit")
    val quantityUnit: String?,
    @Schema(description = "Manufacturer")
    val manufacturer: String?,
    @Schema(description = "Country")
    val country: String?,
    @Schema(description = "Description")
    val description: String?
)

@Schema(description = "Drug information with planned quantity")
data class DrugDTO(
    @Schema(description = "Drug ID")
    val id: UUID,
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @Schema(description = "Current quantity", example = "100.0")
    val quantity: BigDecimal,
    @Schema(description = "Total planned quantity across treatment plans", example = "30.0")
    val plannedQuantity: BigDecimal,
    @Schema(description = "Quantity unit", example = "mg")
    val quantityUnit: String,
    @Schema(description = "Form type", example = "tablet")
    val formType: String?,
    @Schema(description = "Category", example = "painkiller")
    val category: String?,
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @Schema(description = "Country", example = "Germany")
    val country: String?,
    @Schema(description = "Description")
    val description: String?,
    @Schema(description = "Medicine kit ID")
    val medKitId: UUID
)

@Schema(description = "Request to create a new drug")
data class DrugCreateDTO(
    @NotNull
    @Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin", required = true)
    val name: String,

    @NotNull
    @DecimalMin("0.0")
    @Schema(description = "Quantity", example = "100.0", required = true, minimum = "0")
    val quantity: BigDecimal,

    @NotNull
    @Size(min = 1, max = 50)
    @Schema(description = "Quantity unit", example = "mg", required = true)
    val quantityUnit: String,

    @NotNull
    @Schema(description = "Medicine kit ID", required = true)
    val medKitId: UUID,

    @Size(max = 100)
    @Schema(description = "Form type", example = "tablet")
    val formType: String? = null,

    @Size(max = 200)
    @Schema(description = "Category", example = "painkiller")
    val category: String? = null,

    @Size(max = 300)
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String? = null,

    @Size(max = 100)
    @Schema(description = "Country", example = "Germany")
    val country: String? = null,

    @Size(max = 4000)
    @Schema(description = "Description")
    val description: String? = null
)

@Schema(description = "Request to update a drug")
data class DrugUpdateDTO(
    @Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String? = null,

    @DecimalMin("0.0")
    @Schema(description = "Quantity", example = "100.0", minimum = "0")
    val quantity: BigDecimal? = null,

    @Size(min = 1, max = 50)
    @Schema(description = "Quantity unit", example = "mg")
    val quantityUnit: String? = null,

    @Size(max = 100)
    @Schema(description = "Form type", example = "tablet")
    val formType: String? = null,

    @Size(max = 200)
    @Schema(description = "Category", example = "painkiller")
    val category: String? = null,

    @Size(max = 300)
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String? = null,

    @Size(max = 100)
    @Schema(description = "Country", example = "Germany")
    val country: String? = null,

    @Size(max = 4000)
    @Schema(description = "Description")
    val description: String? = null
)
