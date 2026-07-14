package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class QuantityReductionService(
    private val usingRepository: UsingRepository,
    private val drugRepository: DrugRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java)

) {
    fun handleQuantityReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        if (drug.quantity.signum() == 0) {
            drugRepository.delete(drug)  // CascadeType.ALL removes usings
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        // Reducing all fairly
        val reduceFactor = drug.quantity.divide(drug.totalPlannedAmount, 12, RoundingMode.HALF_UP)
        handleUsingReduction(drug.id, reduceFactor, drug.quantity)
        drug.totalPlannedAmount = drug.quantity
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    private fun handleUsingReduction(drugId: UUID, factor: BigDecimal, targetTotal: BigDecimal) {
        val usings = usingRepository.findAllByUsingKeyDrugId(drugId)
            .sortedBy { it.usingKey.userId }
        usings.forEach {
            it.plannedAmount = it.plannedAmount.multiply(factor).setScale(6, RoundingMode.HALF_UP)
        }
        // Put the rounding residual into one deterministic plan so the invariant stays exact.
        if (usings.isNotEmpty()) {
            val roundedTotal = usings.fold(BigDecimal.ZERO) { total, using -> total + using.plannedAmount }
            usings.first().plannedAmount += targetTotal - roundedTotal
        }
        usingRepository.saveAll(usings)

    }
}
