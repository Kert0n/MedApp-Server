package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.kert0n.medappserver.api.DrugConsumptionRequest
import org.kert0n.medappserver.api.DrugCreateDTO
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.toCommand
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toPatch
import org.kert0n.medappserver.api.toTemplateDto
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
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
@Tag(name = "Drugs")
class DrugController(
    private val drugService: DrugService,
    private val vidalDrugService: VidalDrugService,
    private val commands: DrugCommandService
) {

    @GetMapping("/drugs/{drugId}")
    @Operation(summary = "Get an accessible drug")
    fun getDrug(
        authentication: Authentication,
        @PathVariable drugId: UUID
    ): DrugDTO = drugService.getAccessible(authentication.userId, drugId).toDto()

    @PostMapping("/med-kits/{medKitId}/drugs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a drug in a medkit")
    fun createDrug(
        authentication: Authentication,
        @PathVariable medKitId: UUID,
        @Valid @RequestBody request: DrugCreateDTO
    ): DrugDTO = commands.create(authentication.userId, medKitId, request.toCommand()).toDto()

    @PatchMapping("/drugs/{drugId}")
    @Operation(summary = "Correct drug fields or increase stock")
    fun patchDrug(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: DrugPatchRequest
    ): DrugDTO = commands.patch(authentication.userId, drugId, request.toPatch()).toDto()

    @DeleteMapping("/drugs/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDrug(authentication: Authentication, @PathVariable drugId: UUID) {
        commands.delete(authentication.userId, drugId)
    }

    @PostMapping("/drugs/{drugId}/consumptions")
    @Operation(summary = "Consume stock")
    fun consumeDrug(
        authentication: Authentication,
        @PathVariable drugId: UUID,
        @Valid @RequestBody request: DrugConsumptionRequest
    ): DrugDTO? = commands.consume(authentication.userId, drugId, request.quantity)?.toDto()

    @PutMapping("/med-kits/{targetMedKitId}/drugs/{drugId}")
    @Operation(summary = "Idempotently move a drug")
    fun moveDrug(
        authentication: Authentication,
        @PathVariable targetMedKitId: UUID,
        @PathVariable drugId: UUID
    ): DrugDTO = commands.move(authentication.userId, drugId, targetMedKitId).toDto()

    @GetMapping("/drug-templates")
    fun searchDrugTemplates(
        authentication: Authentication,
        @RequestParam @Size(min = 1, max = 200) query: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int
    ): List<DrugTemplateDTO> =
        vidalDrugService.fuzzySearch(query, limit).map { it.toTemplateDto() }

    @GetMapping("/drug-templates/{templateId}")
    fun getDrugTemplate(
        authentication: Authentication,
        @PathVariable templateId: UUID
    ): DrugTemplateDTO = vidalDrugService.findById(templateId).toTemplateDto()
}
