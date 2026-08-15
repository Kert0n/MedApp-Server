@file:Suppress("DEPRECATION")

package org.kert0n.medappserver.controller

import java.math.BigDecimal
import java.util.UUID

/** Temporary source compatibility for legacy services; these types are not exposed by REST v1. */
@Deprecated("Use DTOs from org.kert0n.medappserver.api")
data class DrugDTO(
    val id: UUID,
    val name: String,
    val quantity: BigDecimal,
    val plannedQuantity: BigDecimal,
    val quantityUnit: String,
    val formType: String?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val medKitId: UUID
)

@Deprecated("Use DrugCreateRequest")
data class DrugCreateDTO(
    val name: String,
    val quantity: BigDecimal,
    val quantityUnit: String,
    val medKitId: UUID,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

@Deprecated("Use DrugPatchRequest")
data class DrugUpdateDTO(
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

@Deprecated("Use DrugConsumptionRequest")
data class ConsumeRequest(val quantity: BigDecimal)

@Deprecated("Move target is in the resource path")
data class MoveDrugRequest(val targetMedKitId: UUID)

@Deprecated("Use DrugDTO")
data class QuantityInfo(
    val actualQuantity: BigDecimal,
    val plannedQuantity: BigDecimal,
    val availableQuantity: BigDecimal
)

@Deprecated("Use TreatmentPlanDTO")
data class UsingDTO(val userId: UUID, val drugId: UUID, val plannedAmount: BigDecimal)

@Deprecated("Use TreatmentPlanCreateRequest")
data class UsingCreateDTO(val drugId: UUID, val plannedAmount: BigDecimal)

@Deprecated("Use TreatmentPlanPatchRequest")
data class UsingUpdateDTO(val plannedAmount: BigDecimal)

@Deprecated("Intake identifier is in the resource path")
data class IntakeRequest(val quantityConsumed: BigDecimal, val intakeId: UUID)

@Deprecated("Use MedKitContentDTO")
data class MedKitDTO(val id: UUID, val drugs: Set<DrugDTO>)

@Deprecated("Use org.kert0n.medappserver.api.MedKitSummaryDTO")
data class MedKitSummaryDTO(val id: UUID, val userCount: Long, val drugCount: Long)

@Deprecated("Use UserSnapshotDTO")
data class UserDto(val id: UUID, val medKits: Set<MedKitDTO>)

@Deprecated("Use org.kert0n.medappserver.api.DrugTemplateDTO")
data class DrugTemplateDTO(
    val id: UUID,
    val name: String,
    val formType: String?,
    val category: String?,
    val quantityUnit: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)
