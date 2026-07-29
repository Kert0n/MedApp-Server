package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.PlanSnapshot
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@Service
class TreatmentPlanService(
    private val drugs: DrugRepository,
    private val users: UserRepository,
    private val plans: UsingRepository
) {

    private val logger = LoggerFactory.getLogger(TreatmentPlanService::class.java)

    @Transactional
    fun create(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): Using {
        requirePositive(plannedAmount)
        val drug = lockAccessible(userId, drugId)
        if (plans.findByUserIdAndDrugId(userId, drugId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Treatment plan already exists")
        }
        if (plannedAmount > drug.availableQuantity) insufficient()

        val user = users.findByIdOrNull(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        return plans.save(
            Using(
                usingKey = UsingKey(userId, drugId),
                user = user,
                drug = drug,
                plannedAmount = plannedAmount
            )
        )
    }

    @Transactional
    fun patch(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): Using {
        requirePositive(plannedAmount)
        val drug = lockAccessible(userId, drugId)
        val plan = requirePlan(userId, drugId)
        val availableForPlan = drug.availableQuantity + plan.plannedAmount
        if (plannedAmount > availableForPlan) insufficient()

        plan.plannedAmount = plannedAmount
        return plan
    }

    @Transactional
    fun delete(userId: UUID, drugId: UUID) {
        plans.delete(requirePlan(userId, drugId))
    }

    @Transactional
    fun applyIntake(userId: UUID, drugId: UUID, amount: BigDecimal): PlanSnapshot? {
        requirePositive(amount)
        val drug = lockAccessible(userId, drugId)
        val plan = requirePlan(userId, drugId)
        if (amount > plan.plannedAmount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Consumed quantity exceeds planned amount"
            )
        }
        if (amount > drug.quantity) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Insufficient drug quantity available"
            )
        }
        if ((drug.quantity - amount).isZero()) {
            drugs.deleteLockedById(drugId)
            return null
        }

        plan.reduceBy(amount)
        drug.consumePlanned(amount)
        if (plan.plannedAmount.isZero()) {
            plans.delete(plan)
            return null
        }
        return PlanSnapshot(userId, drugId, plan.plannedAmount)
    }

    private fun lockAccessible(userId: UUID, drugId: UUID): Drug =
        drugs.findAccessibleForUpdate(drugId, userId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Drug not found or access denied"
            )

    private fun requirePlan(userId: UUID, drugId: UUID): Using =
        plans.findByUserIdAndDrugId(userId, drugId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")

    private fun requirePositive(amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive")
        }
    }

    private fun insufficient(): Nothing {
        logger.warn("Rejected treatment plan command: requested amount exceeds availability")
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
    }
}
