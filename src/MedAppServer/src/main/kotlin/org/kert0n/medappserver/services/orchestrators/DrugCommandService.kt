package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.services.models.DrugPatch
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@Service
class DrugCommandService(
    private val drugs: DrugRepository,
    private val medKits: MedKitRepository,
    private val plans: UsingRepository
) {

    private val logger = LoggerFactory.getLogger(DrugCommandService::class.java)

    @Transactional
    fun create(userId: UUID, medKitId: UUID, command: DrugCreation): Drug {
        if (command.quantity <= BigDecimal.ZERO) badRequest("Drug quantity must be positive")
        val medKit = medKits.findAccessible(medKitId, userId) ?: notFound()
        return drugs.save(
            Drug(
                name = command.name,
                quantity = command.quantity,
                quantityUnit = command.quantityUnit,
                formType = command.formType,
                category = command.category,
                manufacturer = command.manufacturer,
                country = command.country,
                description = command.description,
                medKit = medKit
            )
        )
    }

    @Transactional
    fun patch(userId: UUID, drugId: UUID, patch: DrugPatch): Drug {
        val drug = lockAccessible(userId, drugId)
        patch.quantity?.let { newQuantity ->
            if (newQuantity <= drug.quantity) {
                badRequest("Corrected quantity must be greater than current quantity")
            }
            drug.quantity = newQuantity
        }
        patch.name?.let { drug.name = it }
        patch.quantityUnit?.let { drug.quantityUnit = it }
        patch.formType?.let { drug.formType = it }
        patch.category?.let { drug.category = it }
        patch.manufacturer?.let { drug.manufacturer = it }
        patch.country?.let { drug.country = it }
        patch.description?.let { drug.description = it }
        return drug
    }

    @Transactional
    fun consume(userId: UUID, drugId: UUID, amount: BigDecimal): Drug? {
        if (amount <= BigDecimal.ZERO) badRequest("Consumed quantity must be positive")
        val drug = lockAccessible(userId, drugId)
        if (amount > drug.quantity) badRequest("Insufficient quantity available")

        drug.consumeUnplanned(amount)
        if (drug.quantity.isZero()) {
            drugs.deleteLockedById(drugId)
            return null
        }

        if (drug.totalPlannedAmount > drug.quantity) {
            val affectedPlans = plans.findAllByDrugId(drugId)
            val amounts = PlanReconciler.reconcile(
                stock = drug.quantity,
                plannedAmounts = affectedPlans.map { it.plannedAmount }
            )
            affectedPlans.zip(amounts).forEach { (plan, reconciled) ->
                plan.plannedAmount = reconciled
            }
            drug.totalPlannedAmount = amounts.fold(BigDecimal.ZERO, BigDecimal::add)
            logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")
        }
        return drug
    }

    @Transactional
    fun move(userId: UUID, drugId: UUID, targetMedKitId: UUID): Drug {
        val drug = lockAccessible(userId, drugId)
        if (drug.medKit.id == targetMedKitId) return drug

        val target = medKits.findAccessibleWithUsers(targetMedKitId, userId) ?: notFound()
        val targetUserIds = target.users.mapTo(mutableSetOf()) { it.id }
        plans.deleteByDrugIdAndUserIdNotIn(drugId, targetUserIds)
        drugs.moveToMedKit(drugId, targetMedKitId)
        return drugs.findAccessible(drugId, userId) ?: notFound()
    }

    @Transactional
    fun delete(userId: UUID, drugId: UUID) {
        lockAccessible(userId, drugId)
        drugs.deleteLockedById(drugId)
    }

    private fun lockAccessible(userId: UUID, drugId: UUID): Drug =
        drugs.findAccessibleForUpdate(drugId, userId) ?: notFound()

    private fun notFound(): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")

    private fun badRequest(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
