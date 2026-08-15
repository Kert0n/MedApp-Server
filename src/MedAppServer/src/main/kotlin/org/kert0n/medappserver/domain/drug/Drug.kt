package org.kert0n.medappserver.domain.drug

import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.quantity.isPositive
import org.kert0n.medappserver.domain.quantity.isZero
import org.kert0n.medappserver.domain.quantity.toQuantityScale
import java.math.BigDecimal
import java.util.UUID

class Drug private constructor(
    val id: UUID,
    medKitId: UUID,
    name: String,
    quantity: BigDecimal,
    quantityUnit: String,
    formType: String?,
    category: String?,
    manufacturer: String?,
    country: String?,
    description: String?
) {
    var medKitId: UUID = medKitId
        private set
    var name: String = name
        private set
    var quantity: BigDecimal = normalizePositive(quantity)
        private set
    var quantityUnit: String = quantityUnit
        private set
    var formType: String? = formType
        private set
    var category: String? = category
        private set
    var manufacturer: String? = manufacturer
        private set
    var country: String? = country
        private set
    var description: String? = description
        private set

    fun patch(patch: DrugPatch) {
        patch.quantity?.let(::increaseQuantityTo)
        patch.name?.let { name = it }
        patch.quantityUnit?.let { quantityUnit = it }
        patch.formType?.let { formType = it }
        patch.category?.let { category = it }
        patch.manufacturer?.let { manufacturer = it }
        patch.country?.let { country = it }
        patch.description?.let { description = it }
    }

    fun increaseQuantityTo(newQuantity: BigDecimal) {
        val normalized = newQuantity.toQuantityScale()
        if (!normalized.isPositive() || normalized <= quantity) {
            throw InvalidQuantity("New quantity must be greater than current quantity")
        }
        quantity = normalized
    }

    fun consume(amount: BigDecimal, plans: TreatmentPlanBook): ConsumptionDecision {
        requireBook(plans)
        val normalized = normalizePositive(amount)
        if (normalized > quantity) throw InsufficientStock()

        quantity = (quantity - normalized).toQuantityScale()
        if (quantity.isZero()) {
            return ConsumptionDecision(exhausted = true, changedPlans = plans.clear())
        }
        return ConsumptionDecision(exhausted = false, changedPlans = plans.reconcileTo(quantity))
    }

    fun createPlan(userId: UUID, amount: BigDecimal, plans: TreatmentPlanBook): TreatmentPlan {
        requireBook(plans)
        val plan = TreatmentPlan.create(userId, id, amount)
        if (plans.totalPlannedAmount + plan.plannedAmount > quantity) throw PlannedAmountExceedsStock()
        plans.add(plan)
        return plan
    }

    fun changePlan(userId: UUID, amount: BigDecimal, plans: TreatmentPlanBook): TreatmentPlan {
        requireBook(plans)
        val plan = plans.require(userId)
        val normalized = normalizePositive(amount)
        val totalWithoutPlan = plans.totalPlannedAmount - plan.plannedAmount
        if (totalWithoutPlan + normalized > quantity) throw PlannedAmountExceedsStock()
        plan.changeTo(normalized)
        return plan
    }

    fun deletePlan(userId: UUID, plans: TreatmentPlanBook): TreatmentPlan {
        requireBook(plans)
        return plans.remove(userId)
    }

    fun applyIntake(userId: UUID, amount: BigDecimal, plans: TreatmentPlanBook): IntakeDecision {
        requireBook(plans)
        val normalized = normalizePositive(amount)
        val plan = plans.require(userId)
        if (normalized > quantity) throw InsufficientStock()
        if (normalized > plan.plannedAmount) throw PlannedAmountExceedsStock()

        quantity = (quantity - normalized).toQuantityScale()
        plan.reduceBy(normalized)

        if (quantity.isZero()) {
            plans.clear()
            return IntakeDecision(exhausted = true, plan = null)
        }
        if (plan.plannedAmount.isZero()) {
            plans.remove(userId)
            return IntakeDecision(exhausted = false, plan = null)
        }
        return IntakeDecision(exhausted = false, plan = plan.snapshot())
    }

    fun moveTo(targetMedKitId: UUID, accessibleUserIds: Set<UUID>, plans: TreatmentPlanBook): List<TreatmentPlan> {
        requireBook(plans)
        val removedPlans = plans.removeUsersWithoutAccess(accessibleUserIds)
        medKitId = targetMedKitId
        return removedPlans
    }

    private fun requireBook(plans: TreatmentPlanBook) {
        require(plans.drugId == id) { "Treatment plan book belongs to another drug" }
    }

    companion object {
        fun create(command: CreateDrug): Drug = Drug(
            id = command.id,
            medKitId = command.medKitId,
            name = command.name,
            quantity = command.quantity,
            quantityUnit = command.quantityUnit,
            formType = command.formType,
            category = command.category,
            manufacturer = command.manufacturer,
            country = command.country,
            description = command.description
        )

        private fun normalizePositive(amount: BigDecimal): BigDecimal {
            if (!amount.isPositive()) throw InvalidQuantity()
            return amount.toQuantityScale()
        }
    }
}

data class CreateDrug(
    val id: UUID = UUID.randomUUID(),
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

data class DrugPatch(
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

data class ConsumptionDecision(
    val exhausted: Boolean,
    val changedPlans: List<TreatmentPlan>
)

data class IntakeDecision(
    val exhausted: Boolean,
    val plan: TreatmentPlanSnapshot?
)
