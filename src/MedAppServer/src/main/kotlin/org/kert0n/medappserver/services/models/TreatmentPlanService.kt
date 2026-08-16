package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.TreatmentPlanView
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.services.orchestrators.QuantityReductionService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*

@Service
class TreatmentPlanService(
    private val treatmentPlanRepository: TreatmentPlanRepository,
    val logger: Logger = LoggerFactory.getLogger(TreatmentPlanService::class.java),
    private val userService: UserService,
    private val drugService: DrugService,
    private val quantityReductionService: QuantityReductionService
) {


    // ── Чтение ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun viewsOf(userId: UUID): List<TreatmentPlanView> {
        logger.debug("Reading treatment plans of user {}", userId)
        return treatmentPlanRepository.findViewsOf(userId)
    }

    /** План для показа или `null`, если его нет. */
    @Transactional(readOnly = true)
    fun findView(userId: UUID, drugId: UUID): TreatmentPlanView? =
        treatmentPlanRepository.findView(userId, drugId)

    /** План для показа или 404. */
    @Transactional(readOnly = true)
    fun requireView(userId: UUID, drugId: UUID): TreatmentPlanView =
        findView(userId, drugId) ?: throw noSuchPlan()

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<TreatmentPlan> {
        logger.debug("Finding all treatment plans for user: {}", userId)
        return treatmentPlanRepository.findAllByPlanKeyUserId(userId)
    }

    /**
     * Удаляет планы всех, кроме перечисленных пользователей.
     *
     * Нужна при переносе препарата: планы тех, у кого нет доступа к целевой аптечке, дальше
     * не действуют.
     */
    @Transactional
    fun deletePlansExcept(drugId: UUID, keepUserIds: Set<UUID>) {
        val doomed = treatmentPlanRepository.findAllByPlanKeyDrugId(drugId).filter { it.user.id !in keepUserIds }
        if (doomed.isNotEmpty()) {
            treatmentPlanRepository.deleteAll(doomed)
        }
    }

    @Transactional
    fun deleteAllByUserIdInMedkit(userId: UUID, medKitId: UUID) {
        logger.debug("Deleting all treatment plans for user: {}", userId)
        treatmentPlanRepository.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    @Transactional(readOnly = true)
    fun findAllByDrug(drugId: UUID): List<TreatmentPlan> {
        logger.debug("Finding all treatment plans for drug: {}", drugId)
        return treatmentPlanRepository.findAllByPlanKeyDrugId(drugId)
    }

    @Transactional(readOnly = true)
    fun findPlan(userId: UUID, drugId: UUID): TreatmentPlan? =
        treatmentPlanRepository.findByUserIdAndDrugId(userId, drugId)

    @Transactional(readOnly = true)
    fun requirePlan(userId: UUID, drugId: UUID): TreatmentPlan {
        logger.debug("Finding treatment plan for user {} and drug {}", userId, drugId)
        return findPlan(userId, drugId) ?: throw noSuchPlan()
    }

    private fun noSuchPlan() = ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such treatment plan")

    @Transactional
    fun createTreatmentPlan(userId: UUID, createDTO: TreatmentPlanCreateRequest): TreatmentPlan {
        logger.debug("Creating treatment for user {} and drug {}", userId, createDTO.drugId)


        val user = userService.findById(userId)
        val drug = drugService.lockAccessible(createDTO.drugId, userId)

        if (treatmentPlanRepository.findByUserIdAndDrugId(userId, createDTO.drugId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "treatment plan already exists for this user and drug")
        }

        // Validate planned quantity against currently reserved amounts to avoid overbooking stock.
        val currentPlanned = drug.totalPlannedAmount
        val availableQuantity = drug.quantity - currentPlanned

        if (createDTO.plannedAmount > availableQuantity) {
            // No amounts in the message or the log line: both end up somewhere readable,
            // and a drug plus a quantity is the kind of detail this server does not hand out.
            logger.warn("Rejected treatment plan: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        val plan = TreatmentPlan(
            planKey = TreatmentPlanKey(userId, createDTO.drugId),
            user = user,
            drug = drug,
            plannedAmount = createDTO.plannedAmount
        )

        return treatmentPlanRepository.save(plan)
    }

    @Transactional
    fun updateTreatmentPlan(userId: UUID, drugId: UUID, updateDTO: TreatmentPlanPatchRequest): TreatmentPlan {
        logger.debug("Updating treatment plan for user {} and drug {}", userId, drugId)

        // Lock the drug row to prevent concurrent plan modifications
        drugService.lockAccessible(drugId, userId)
        val plan = requirePlan(userId, drugId)
        // Exclude the current plan when checking availability.
        val otherPlanned = plan.drug.totalPlannedAmount - plan.plannedAmount
        val availableQuantity = plan.drug.quantity - otherPlanned

        if (updateDTO.plannedAmount > availableQuantity) {
            logger.warn("Rejected treatment plan update: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        plan.plannedAmount = updateDTO.plannedAmount

        return treatmentPlanRepository.save(plan)
    }

    /**
     * Списывает приём с плана и с остатка препарата.
     *
     * `null` означает «план исчерпан и удалён», а не «план не найден»: отсутствующий план
     * отвергается 404 ещё до списания.
     */
    @Transactional
    fun recordIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): TreatmentPlan? {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)
        val plan = requirePlan(userId, drugId)
        // Check if consumed quantity exceeds planned amount
        if (quantityConsumed > plan.plannedAmount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Consumed quantity exceeds planned amount"
            )
        }

        if (quantityConsumed > plan.drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient drug quantity available")
        }

        // Update planned amount
        // IMPORTANT! THIS MUST ALWAYS BE BEFORE QUANTITY REDUCTION, SO IT CAN PROPERLY ASSESS TOTAL PLANNED QUANTITY
        plan.plannedAmount = maxOf(BigDecimal.ZERO, plan.plannedAmount - quantityConsumed)
        // Reduce drug quantity
        plan.drug.quantity -= quantityConsumed
        // This could be replaced with reloading drug from db, but this much quicker
        plan.drug.totalPlannedAmount -= quantityConsumed
        quantityReductionService.handleQuantityReduction(plan.drug)
        if (plan.plannedAmount.isZero()) {
            treatmentPlanRepository.delete(plan)
            return null
        }
        return treatmentPlanRepository.save(plan)
    }


    @Transactional
    fun deleteTreatmentPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting treatment plan for user {} and drug {}", userId, drugId)
        val plan = requirePlan(userId, drugId)
        treatmentPlanRepository.delete(plan)
    }


}
