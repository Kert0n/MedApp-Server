package org.kert0n.medappserver.domain.drug

import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.quantity.isPositive
import org.kert0n.medappserver.domain.quantity.toQuantityScale
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class TreatmentPlan private constructor(
    val userId: UUID,
    val drugId: UUID,
    plannedAmount: BigDecimal
) {
    var plannedAmount: BigDecimal = normalizePositive(plannedAmount)
        private set

    internal fun changeTo(amount: BigDecimal) {
        plannedAmount = normalizePositive(amount)
    }

    internal fun reduceBy(amount: BigDecimal) {
        plannedAmount = (plannedAmount - amount).toQuantityScale()
    }

    internal fun reconcileTo(amount: BigDecimal) {
        plannedAmount = amount.toQuantityScale(RoundingMode.DOWN)
    }

    fun snapshot(): TreatmentPlanSnapshot = TreatmentPlanSnapshot(userId, drugId, plannedAmount)

    companion object {
        fun create(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan =
            TreatmentPlan(userId, drugId, plannedAmount)

        private fun normalizePositive(amount: BigDecimal): BigDecimal {
            if (!amount.isPositive()) throw InvalidQuantity("Planned amount must be positive")
            return amount.toQuantityScale()
        }
    }
}

data class TreatmentPlanSnapshot(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
)
