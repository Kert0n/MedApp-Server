package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Drug with stock and treatment-plan totals")
data class DrugDTO(
    @field:Schema(description = "Drug identifier") val id: UUID,
    @field:Schema(description = "Medicine kit identifier") val medKitId: UUID,
    @field:Schema(description = "Drug name", example = "Aspirin") val name: String,
    @field:Schema(description = "Current stock", example = "100.000000") val quantity: BigDecimal,
    @field:Schema(description = "Total amount reserved by treatment plans") val plannedQuantity: BigDecimal,
    @field:Schema(description = "Unreserved stock") val availableQuantity: BigDecimal,
    @field:Schema(description = "Unit used for stock and plans", example = "tablet") val quantityUnit: String,
    @field:Schema(description = "Dosage form", nullable = true) val formType: String?,
    @field:Schema(description = "Drug category", nullable = true) val category: String?,
    @field:Schema(description = "Manufacturer", nullable = true) val manufacturer: String?,
    @field:Schema(description = "Country of manufacture", nullable = true) val country: String?,
    @field:Schema(description = "Free-form description", nullable = true) val description: String?
)

@Schema(description = "Fields required to create a drug in the medicine kit from the path")
data class DrugCreateRequest(
    @field:NotBlank @field:Size(max = 300)
    @field:Schema(description = "Drug name", example = "Aspirin") val name: String,
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "Initial stock; must be positive") val quantity: BigDecimal,
    @field:NotBlank @field:Size(max = 50)
    @field:Schema(description = "Quantity unit", example = "tablet") val quantityUnit: String,
    @field:Size(max = 100) @field:Schema(description = "Dosage form") val formType: String? = null,
    @field:Size(max = 200) @field:Schema(description = "Drug category") val category: String? = null,
    @field:Size(max = 300) @field:Schema(description = "Manufacturer") val manufacturer: String? = null,
    @field:Size(max = 100) @field:Schema(description = "Country of manufacture") val country: String? = null,
    @field:Size(max = 4000) @field:Schema(description = "Free-form description") val description: String? = null
)

@Schema(description = "Partial correction; null means that the field is unchanged")
data class DrugPatchRequest(
    @field:Size(min = 1, max = 300) @field:Schema(description = "Corrected name") val name: String? = null,
    @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "New stock; when present it must exceed current stock") val quantity: BigDecimal? = null,
    @field:Size(min = 1, max = 50) @field:Schema(description = "Corrected quantity unit") val quantityUnit: String? = null,
    @field:Size(max = 100) @field:Schema(description = "Corrected dosage form") val formType: String? = null,
    @field:Size(max = 200) @field:Schema(description = "Corrected category") val category: String? = null,
    @field:Size(max = 300) @field:Schema(description = "Corrected manufacturer") val manufacturer: String? = null,
    @field:Size(max = 100) @field:Schema(description = "Corrected country") val country: String? = null,
    @field:Size(max = 4000) @field:Schema(description = "Corrected description") val description: String? = null
)

@Schema(description = "Unplanned stock consumption")
data class DrugConsumptionRequest(
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "Positive amount to consume") val quantity: BigDecimal
)

@Schema(description = "Drug catalogue template")
data class DrugTemplateDTO(
    @field:Schema(description = "Template identifier") val id: UUID,
    @field:Schema(description = "Catalogue name") val name: String,
    @field:Schema(description = "Dosage form", nullable = true) val formType: String?,
    @field:Schema(description = "Category", nullable = true) val category: String?,
    @field:Schema(description = "Quantity unit", nullable = true) val quantityUnit: String?,
    @field:Schema(description = "Manufacturer", nullable = true) val manufacturer: String?,
    @field:Schema(description = "Country", nullable = true) val country: String?,
    @field:Schema(description = "Description", nullable = true) val description: String?
)
