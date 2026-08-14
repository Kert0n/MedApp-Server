package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Request to consume stock")
data class DrugConsumptionRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(description = "Positive quantity to consume", example = "2.0", minimum = "0")
    val quantity: BigDecimal
)

@Schema(description = "Drug template from the catalog")
data class DrugTemplateDTO(
    @field:Schema(description = "Catalog template identifier")
    val id: UUID,
    @field:Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @field:Schema(description = "International name in Latin script", example = "Aspirin")
    val nameLat: String?,
    @field:Schema(description = "Active substance", example = "Acetylsalicylic acid")
    val activeSubstance: String?,
    @field:Schema(description = "Dosage form", example = "tablet")
    val formType: String?,
    @field:Schema(description = "Catalog category", example = "Analgesics")
    val category: String?,
    @field:Schema(description = "Quantity unit", example = "tablet")
    val quantityUnit: String?,
    @field:Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @field:Schema(description = "Country of manufacture", example = "Germany")
    val country: String?,
    @field:Schema(description = "Catalog description")
    val description: String?
)

@Schema(description = "Drug stock and descriptive fields")
data class DrugDTO(
    @field:Schema(description = "Drug identifier")
    val id: UUID,
    @field:Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @field:Schema(description = "Current stock", example = "100.0")
    val quantity: BigDecimal,
    @field:Schema(description = "Sum reserved by all treatment plans", example = "30.0")
    val plannedQuantity: BigDecimal,
    @field:Schema(description = "Unreserved stock: quantity minus plannedQuantity", example = "70.0")
    val availableQuantity: BigDecimal,
    @field:Schema(description = "Quantity unit", example = "mg")
    val quantityUnit: String,
    @field:Schema(description = "Dosage form", example = "tablet")
    val formType: String?,
    @field:Schema(description = "User-defined category", example = "painkiller")
    val category: String?,
    @field:Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @field:Schema(description = "Country of manufacture", example = "Germany")
    val country: String?,
    @field:Schema(description = "User-defined description")
    val description: String?,
    @field:Schema(description = "Identifier of the containing medkit")
    val medKitId: UUID
)

@Schema(description = "Request to create a drug in the medkit identified by the path")
data class DrugCreateDTO(
    @field:NotNull
    @field:Size(min = 1, max = 300)
    @field:Schema(description = "Drug name", example = "Aspirin")
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(description = "Initial positive stock", example = "100.0", minimum = "0")
    val quantity: BigDecimal,

    @field:NotNull
    @field:Size(min = 1, max = 50)
    @field:Schema(description = "Quantity unit", example = "mg")
    val quantityUnit: String,

    @field:Size(max = 100)
    @field:Schema(description = "Dosage form", example = "tablet")
    val formType: String? = null,

    @field:Size(max = 200)
    @field:Schema(description = "User-defined category", example = "painkiller")
    val category: String? = null,

    @field:Size(max = 300)
    @field:Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String? = null,

    @field:Size(max = 100)
    @field:Schema(description = "Country of manufacture", example = "Germany")
    val country: String? = null,

    @field:Size(max = 4000)
    @field:Schema(description = "User-defined description")
    val description: String? = null
)

@Schema(description = "Partial correction; null means that the field is unchanged")
data class DrugPatchRequest(
    @field:Size(min = 1, max = 300)
    @field:Schema(description = "Corrected name; null leaves it unchanged", example = "Aspirin")
    val name: String? = null,

    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(
        description = "Corrected stock; must be greater than the current stock, null leaves it unchanged",
        example = "120.0",
        minimum = "0"
    )
    val quantity: BigDecimal? = null,

    @field:Size(min = 1, max = 50)
    @field:Schema(description = "Corrected quantity unit; null leaves it unchanged", example = "mg")
    val quantityUnit: String? = null,

    @field:Size(max = 100)
    @field:Schema(description = "Corrected dosage form; null leaves it unchanged", example = "tablet")
    val formType: String? = null,

    @field:Size(max = 200)
    @field:Schema(description = "Corrected category; null leaves it unchanged", example = "painkiller")
    val category: String? = null,

    @field:Size(max = 300)
    @field:Schema(description = "Corrected manufacturer; null leaves it unchanged", example = "Bayer")
    val manufacturer: String? = null,

    @field:Size(max = 100)
    @field:Schema(description = "Corrected country; null leaves it unchanged", example = "Germany")
    val country: String? = null,

    @field:Size(max = 4000)
    @field:Schema(description = "Corrected description; null leaves it unchanged")
    val description: String? = null
)
