package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

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
    @Schema(description = "International name in Latin script", example = "Aspirin")
    val nameLat: String?,
    @Schema(description = "Active substance", example = "Acetylsalicylic acid")
    val activeSubstance: String?,
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
