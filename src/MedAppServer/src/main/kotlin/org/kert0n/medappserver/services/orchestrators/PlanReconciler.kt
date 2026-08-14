package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.QUANTITY_ROUNDING
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import java.math.BigDecimal
import java.math.RoundingMode

object PlanReconciler {

    fun reconcile(stock: BigDecimal, plannedAmounts: List<BigDecimal>): List<BigDecimal> {
        val total = plannedAmounts.fold(BigDecimal.ZERO, BigDecimal::add)
        if (plannedAmounts.isEmpty() || total <= stock) return plannedAmounts

        val factor = stock.divide(total, QUANTITY_SCALE + 10, QUANTITY_ROUNDING)
        return plannedAmounts.map { amount ->
            (amount * factor).setScale(QUANTITY_SCALE, RoundingMode.DOWN)
        }
    }
}
