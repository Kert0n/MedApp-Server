package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Service
class QuantityReductionService(
    private val treatmentPlanRepository: TreatmentPlanRepository,
    private val drugRepository: DrugRepository,
    val logger: Logger = LoggerFactory.getLogger(TreatmentPlanService::class.java)

) {
    fun handleQuantityReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        if (drug.quantity.isZero()) {
            drugRepository.delete(drug)  // CascadeType.ALL removes treatment plans
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        // Drug id and amounts left out on purpose: together they describe someone's stock.
        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        // Reducing all fairly
        handlePlanReduction(drug.id, remaining = drug.quantity, planned = drug.totalPlannedAmount)
        drug.totalPlannedAmount = drug.quantity
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    /**
     * Сжимает планы до остатка пропорционально их размеру.
     *
     * Умножение идёт до деления: отдельный коэффициент `remaining / planned` пришлось бы
     * округлить, и деление 60 на 90 превратило бы план 30 в 19.999999 вместо 20. При таком
     * порядке точное частное получается там, где оно вообще существует.
     *
     * Округление вниз на каждом плане оставляет инвариант в силе: сумма точных долей равна
     * остатку, значит сумма округлённых вниз его не превышает.
     */
    private fun handlePlanReduction(drugId: UUID, remaining: BigDecimal, planned: BigDecimal) {
        val plans = treatmentPlanRepository.findAllByPlanKeyDrugId(drugId)
        plans.forEach {
            it.plannedAmount = it.plannedAmount
                .multiply(remaining)
                .divide(planned, QUANTITY_SCALE, RoundingMode.DOWN)
        }
        treatmentPlanRepository.saveAll(plans)

    }
}
