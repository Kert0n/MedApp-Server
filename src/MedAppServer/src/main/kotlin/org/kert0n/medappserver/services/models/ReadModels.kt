package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.repository.MedKitSummary
import java.math.BigDecimal
import java.util.UUID

data class DrugView(
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

data class TreatmentPlanView(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
)

data class MedKitContentView(
    val id: UUID,
    val drugs: List<DrugView>
)

data class MedKitSummaryView(
    val id: UUID,
    val userCount: Long,
    val drugCount: Long
)

data class UserSnapshotView(val medKits: List<MedKitContentView>)

fun Drug.toView(): DrugView = DrugView(
    id = id,
    name = name,
    quantity = quantity,
    plannedQuantity = totalPlannedAmount,
    availableQuantity = availableQuantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKit.id
)

fun MedKitSummary.toView(): MedKitSummaryView =
    MedKitSummaryView(id, userCount, drugCount)
