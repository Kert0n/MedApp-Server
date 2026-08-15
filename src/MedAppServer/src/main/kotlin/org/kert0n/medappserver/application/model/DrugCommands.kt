package org.kert0n.medappserver.application.model

import org.kert0n.medappserver.domain.quantity.toQuantityScale
import java.math.BigDecimal
import java.util.UUID

data class CreateDrugCommand(
    val medKitId: UUID,
    val name: String,
    val quantity: BigDecimal,
    val quantityUnit: String,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

data class PatchDrugCommand(
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

data class CreateTreatmentPlanCommand(
    val drugId: UUID,
    val plannedAmount: BigDecimal
)

data class IntakePayload(
    val drugId: UUID,
    val quantity: BigDecimal
) {
    val normalizedQuantity: BigDecimal = quantity.toQuantityScale()

    override fun equals(other: Any?): Boolean =
        other is IntakePayload && drugId == other.drugId && normalizedQuantity == other.normalizedQuantity

    override fun hashCode(): Int = 31 * drugId.hashCode() + normalizedQuantity.hashCode()
}
