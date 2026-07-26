package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

@Service
class QuantityReductionService(
    private val usingRepository: UsingRepository,
    private val drugRepository: DrugRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java)

) {
    fun handleQuantityReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        if (drug.quantity == 0.0) {
            drugRepository.delete(drug)  // CascadeType.ALL removes usings
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        // Drug id and amounts left out on purpose: together they describe someone's stock.
        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        // Reducing all fairly
        val reduceFactor = drug.quantity / drug.totalPlannedAmount
        handleUsingReduction(drug.id, reduceFactor)
        drug.totalPlannedAmount = drug.quantity
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    private fun handleUsingReduction(drugId: UUID, factor: Double) {
        val usings = usingRepository.findAllByUsingKeyDrugId(drugId)
        usings.forEach {
            it.plannedAmount *= factor
        }
        usingRepository.saveAll(usings)

    }
}