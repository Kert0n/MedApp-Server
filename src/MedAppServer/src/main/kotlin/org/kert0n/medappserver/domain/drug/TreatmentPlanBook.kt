package org.kert0n.medappserver.domain.drug

import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
import org.kert0n.medappserver.domain.error.TreatmentPlanNotFound
import org.kert0n.medappserver.domain.quantity.QUANTITY_SCALE
import org.kert0n.medappserver.domain.quantity.toQuantityScale
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class TreatmentPlanBook(
    val drugId: UUID,
    plans: Collection<TreatmentPlan> = emptyList()
) {
    private val plansByUser = plans.associateByTo(linkedMapOf(), TreatmentPlan::userId)

    init {
        require(plans.all { it.drugId == drugId }) { "Treatment plan belongs to another drug" }
        require(plansByUser.size == plans.size) { "Treatment plans must be unique per user and drug" }
    }

    val totalPlannedAmount: BigDecimal
        get() = plansByUser.values.fold(BigDecimal.ZERO) { total, plan -> total + plan.plannedAmount }
            .toQuantityScale()

    val size: Int get() = plansByUser.size

    fun all(): List<TreatmentPlan> = plansByUser.values.toList()

    fun find(userId: UUID): TreatmentPlan? = plansByUser[userId]

    fun require(userId: UUID): TreatmentPlan =
        find(userId) ?: throw TreatmentPlanNotFound(userId, drugId)

    fun add(plan: TreatmentPlan) {
        require(plan.drugId == drugId) { "Treatment plan belongs to another drug" }
        if (plansByUser.putIfAbsent(plan.userId, plan) != null) {
            throw TreatmentPlanAlreadyExists(plan.userId, drugId)
        }
    }

    fun remove(userId: UUID): TreatmentPlan =
        plansByUser.remove(userId) ?: throw TreatmentPlanNotFound(userId, drugId)

    fun removeUsersWithoutAccess(accessibleUserIds: Set<UUID>): List<TreatmentPlan> {
        val removed = plansByUser.values.filter { it.userId !in accessibleUserIds }
        removed.forEach { plansByUser.remove(it.userId) }
        return removed
    }

    fun clear(): List<TreatmentPlan> = plansByUser.values.toList().also { plansByUser.clear() }

    fun reconcileTo(stock: BigDecimal): List<TreatmentPlan> {
        val total = totalPlannedAmount
        if (total <= stock || plansByUser.isEmpty()) return emptyList()

        val factor = stock.divide(total, QUANTITY_SCALE + 8, RoundingMode.DOWN)
        return plansByUser.values.mapNotNull { plan ->
            val reconciled = plan.plannedAmount.multiply(factor).toQuantityScale(RoundingMode.DOWN)
            if (reconciled == plan.plannedAmount) null else plan.also { it.reconcileTo(reconciled) }
        }
    }
}
