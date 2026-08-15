package org.kert0n.medappserver.application.model

import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.drug.TreatmentPlan
import org.kert0n.medappserver.domain.drug.TreatmentPlanSnapshot
import java.math.BigDecimal
import java.util.UUID

data class DrugResult(
    val id: UUID,
    val medKitId: UUID,
    val name: String,
    val quantity: BigDecimal,
    val plannedQuantity: BigDecimal,
    val availableQuantity: BigDecimal,
    val quantityUnit: String,
    val formType: String?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
) {
    companion object {
        fun from(drug: Drug, plannedQuantity: BigDecimal): DrugResult = DrugResult(
            id = drug.id,
            medKitId = drug.medKitId,
            name = drug.name,
            quantity = drug.quantity,
            plannedQuantity = plannedQuantity,
            availableQuantity = drug.quantity - plannedQuantity,
            quantityUnit = drug.quantityUnit,
            formType = drug.formType,
            category = drug.category,
            manufacturer = drug.manufacturer,
            country = drug.country,
            description = drug.description
        )
    }
}

data class TreatmentPlanResult(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
) {
    companion object {
        fun from(plan: TreatmentPlan): TreatmentPlanResult =
            TreatmentPlanResult(plan.userId, plan.drugId, plan.plannedAmount)

        fun from(plan: TreatmentPlanSnapshot): TreatmentPlanResult =
            TreatmentPlanResult(plan.userId, plan.drugId, plan.plannedAmount)
    }
}

data class IntakeResult(
    val drug: DrugResult?,
    val plan: TreatmentPlanResult?
)

data class IntakeCacheEntry(
    val payload: IntakePayload,
    val result: IntakeResult
)
