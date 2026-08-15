package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.kert0n.medappserver.api.DrugConsumptionRequest
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.PatchDrugCommand
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.query.CatalogueQueryService
import org.kert0n.medappserver.application.query.DrugQueryService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
@Tag(name = "Drugs", description = "Stock stored in medicine kits")
class DrugController(
    private val queries: DrugQueryService,
    private val commands: DrugOrchestrator
) {
    @GetMapping("/drugs/{drugId}")
    @Operation(summary = "Get an accessible drug")
    fun get(authentication: Authentication, @PathVariable drugId: UUID): DrugDTO =
        queries.getAccessible(authentication.userId, drugId).toDto()

    @PostMapping("/med-kits/{medKitId}/drugs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a drug in the medicine kit")
    fun create(
        authentication: Authentication,
        @PathVariable medKitId: UUID,
        @Valid @RequestBody request: DrugCreateRequest
    ): DrugDTO = commands.create(
        authentication.userId,
        CreateDrugCommand(
            medKitId = medKitId,
            name = request.name,
            quantity = request.quantity,
            quantityUnit = request.quantityUnit,
            formType = request.formType,
            category = request.category,
            manufacturer = request.manufacturer,
            country = request.country,
            description = request.description
        )
    ).toDto()

    @PatchMapping("/drugs/{drugId}")
    @Operation(summary = "Correct drug fields or increase stock")
    fun patch(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: DrugPatchRequest
    ): DrugDTO = commands.patch(
        authentication.userId,
        drugId,
        PatchDrugCommand(
            name = request.name,
            quantity = request.quantity,
            quantityUnit = request.quantityUnit,
            formType = request.formType,
            category = request.category,
            manufacturer = request.manufacturer,
            country = request.country,
            description = request.description
        )
    ).toDto()

    @DeleteMapping("/drugs/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a drug")
    fun delete(authentication: Authentication, @PathVariable drugId: UUID) {
        commands.delete(authentication.userId, drugId)
    }

    @PostMapping("/drugs/{drugId}/consumptions")
    @Operation(summary = "Consume unplanned stock")
    fun consume(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: DrugConsumptionRequest
    ): DrugDTO? = commands.consume(authentication.userId, drugId, request.quantity)?.toDto()

    @PutMapping("/med-kits/{targetMedKitId}/drugs/{drugId}")
    @Operation(summary = "Move a drug to another medicine kit")
    fun move(
        authentication: Authentication,
        @PathVariable targetMedKitId: UUID,
        @PathVariable drugId: UUID
    ): DrugDTO = commands.move(authentication.userId, drugId, targetMedKitId).toDto()
}

@RestController
@RequestMapping("/v1/drug-templates")
@Tag(name = "Drug catalogue", description = "Searchable drug templates")
class DrugTemplateController(
    private val queries: CatalogueQueryService
) {
    @GetMapping
    @Operation(summary = "Search drug templates")
    fun search(
        @RequestParam(defaultValue = "") @Size(max = 200) query: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int
    ): List<DrugTemplateDTO> = queries.search(query, limit).map { it.toDto() }

    @GetMapping("/{templateId}")
    @Operation(summary = "Get a drug template")
    fun get(@PathVariable templateId: UUID): DrugTemplateDTO = queries.get(templateId).toDto()
}
