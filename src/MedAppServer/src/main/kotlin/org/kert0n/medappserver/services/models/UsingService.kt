package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.TreatmentPlanView
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.orchestrators.QuantityReductionService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Service
class UsingService(
    private val usingRepository: UsingRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java),
    private val userService: UserService,
    private val drugService: DrugService,
    private val quantityReductionService: QuantityReductionService
) {


    // ── Чтение ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun viewsOf(userId: UUID): List<TreatmentPlanView> {
        logger.debug("Reading treatment plans of user {}", userId)
        return usingRepository.findViewsOf(userId)
    }

    /** План для показа или `null`, если его нет. */
    @Transactional(readOnly = true)
    fun findView(userId: UUID, drugId: UUID): TreatmentPlanView? =
        usingRepository.findView(userId, drugId)

    /** План для показа или 404. */
    @Transactional(readOnly = true)
    fun requireView(userId: UUID, drugId: UUID): TreatmentPlanView =
        findView(userId, drugId) ?: throw noSuchPlan()

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<Using> {
        logger.debug("Finding all usings for user: {}", userId)
        return usingRepository.findAllByUsingKeyUserId(userId)
    }

    /**
     * Удаляет планы всех, кроме перечисленных пользователей.
     *
     * Нужна при переносе препарата: планы тех, у кого нет доступа к целевой аптечке, дальше
     * не действуют.
     */
    @Transactional
    fun deletePlansExcept(drugId: UUID, keepUserIds: Set<UUID>) {
        val doomed = usingRepository.findAllByUsingKeyDrugId(drugId).filter { it.user.id !in keepUserIds }
        if (doomed.isNotEmpty()) {
            usingRepository.deleteAll(doomed)
        }
    }

    @Transactional
    fun deleteAllByUserIdInMedkit(userId: UUID, medKitId: UUID) {
        logger.debug("Deleting all usings for user: {}", userId)
        usingRepository.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    @Transactional(readOnly = true)
    fun findAllByDrug(drugId: UUID): List<Using> {
        logger.debug("Finding all usings for drug: {}", drugId)
        return usingRepository.findAllByUsingKeyDrugId(drugId)
    }

    @Transactional(readOnly = true)
    fun findPlan(userId: UUID, drugId: UUID): Using? =
        usingRepository.findByUserIdAndDrugId(userId, drugId)

    @Transactional(readOnly = true)
    fun requirePlan(userId: UUID, drugId: UUID): Using {
        logger.debug("Finding using for user {} and drug {}", userId, drugId)
        return findPlan(userId, drugId) ?: throw noSuchPlan()
    }

    private fun noSuchPlan() = ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such using")

    @Transactional
    fun createTreatmentPlan(userId: UUID, createDTO: TreatmentPlanCreateRequest): Using {
        logger.debug("Creating treatment for user {} and drug {}", userId, createDTO.drugId)


        val user = userService.findById(userId)
        val drug = drugService.lockAccessible(createDTO.drugId, userId)

        if (usingRepository.findByUserIdAndDrugId(userId, createDTO.drugId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "using already exists for this user and drug")
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

        val using = Using(
            usingKey = UsingKey(userId, createDTO.drugId),
            user = user,
            drug = drug,
            plannedAmount = createDTO.plannedAmount,
            lastModified = Instant.now(),
            createdAt = Instant.now()
        )

        return usingRepository.save(using)
    }

    @Transactional
    fun updateTreatmentPlan(userId: UUID, drugId: UUID, updateDTO: TreatmentPlanPatchRequest): Using {
        logger.debug("Updating using for user {} and drug {}", userId, drugId)

        // Lock the drug row to prevent concurrent plan modifications
        drugService.lockAccessible(drugId, userId)
        val using = requirePlan(userId, drugId)
        // Exclude the current plan when checking availability.
        val otherPlanned = using.drug.totalPlannedAmount - using.plannedAmount
        val availableQuantity = using.drug.quantity - otherPlanned

        if (updateDTO.plannedAmount > availableQuantity) {
            logger.warn("Rejected treatment plan update: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        using.plannedAmount = updateDTO.plannedAmount
        using.lastModified = Instant.now()

        return usingRepository.save(using)
    }

    /**
     * Списывает приём с плана и с остатка препарата.
     *
     * `null` означает «план исчерпан и удалён», а не «план не найден»: отсутствующий план
     * отвергается 404 ещё до списания.
     */
    @Transactional
    fun recordIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): Using? {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)
        val using = requirePlan(userId, drugId)
        // Check if consumed quantity exceeds planned amount
        if (quantityConsumed > using.plannedAmount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Consumed quantity exceeds planned amount"
            )
        }

        if (quantityConsumed > using.drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient drug quantity available")
        }

        // Update planned amount
        // IMPORTANT! THIS MUST ALWAYS BE BEFORE QUANTITY REDUCTION, SO IT CAN PROPERLY ASSESS TOTAL PLANNED QUANTITY
        using.plannedAmount = maxOf(BigDecimal.ZERO, using.plannedAmount - quantityConsumed)
        // Reduce drug quantity
        using.drug.quantity -= quantityConsumed
        // This could be replaced with reloading drug from db, but this much quicker
        using.drug.totalPlannedAmount -= quantityConsumed
        quantityReductionService.handleQuantityReduction(using.drug)
        if (using.plannedAmount.isZero()) {
            usingRepository.delete(using)
            return null
        }
        using.lastModified = Instant.now()

        return usingRepository.save(using)
    }


    @Transactional
    fun deleteTreatmentPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting using for user {} and drug {}", userId, drugId)
        val using = requirePlan(userId, drugId)
        usingRepository.delete(using)
    }


}
