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
    val quantity: BigDecimal
)

@Schema(description = "Drug template from the catalog")
data class DrugTemplateDTO(
    val id: UUID,
    val name: String,
    val nameLat: String?,
    val activeSubstance: String?,
    val formType: String?,
    val category: String?,
    val quantityUnit: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)

@Schema(description = "Drug stock and descriptive fields")
data class DrugDTO(
    val id: UUID,
    val name: String,
    val quantity: BigDecimal,
    val plannedQuantity: BigDecimal,
    val availableQuantity: BigDecimal,
    val quantityUnit: String,
    val formType: String?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val medKitId: UUID
)

@Schema(description = "Request to create a drug in the medkit identified by the path")
data class DrugCreateDTO(
    @field:NotNull
    @field:Size(min = 1, max = 300)
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val quantity: BigDecimal,

    @field:NotNull
    @field:Size(min = 1, max = 50)
    val quantityUnit: String,

    @field:Size(max = 100)
    val formType: String? = null,

    @field:Size(max = 200)
    val category: String? = null,

    @field:Size(max = 300)
    val manufacturer: String? = null,

    @field:Size(max = 100)
    val country: String? = null,

    @field:Size(max = 4000)
    val description: String? = null
)

@Schema(description = "Partial correction; null means that the field is unchanged")
data class DrugPatchRequest(
    @field:Size(min = 1, max = 300)
    val name: String? = null,

    @field:DecimalMin(value = "0.0", inclusive = false)
    val quantity: BigDecimal? = null,

    @field:Size(min = 1, max = 50)
    val quantityUnit: String? = null,

    @field:Size(max = 100)
    val formType: String? = null,

    @field:Size(max = 200)
    val category: String? = null,

    @field:Size(max = 300)
    val manufacturer: String? = null,

    @field:Size(max = 100)
    val country: String? = null,

    @field:Size(max = 4000)
    val description: String? = null
)
