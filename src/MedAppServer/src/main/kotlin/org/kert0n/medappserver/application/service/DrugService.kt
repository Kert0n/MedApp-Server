package org.kert0n.medappserver.application.service

import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.IntakeResult
import org.kert0n.medappserver.application.model.PatchDrugCommand
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.kert0n.medappserver.domain.drug.CreateDrug
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.drug.DrugPatch
import org.kert0n.medappserver.domain.error.DrugNotFound
import org.kert0n.medappserver.domain.error.ConcurrentAggregateChange
import org.kert0n.medappserver.persistence.repository.DrugAggregateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service("aggregateDrugService")
class DrugService(
    private val repository: DrugAggregateRepository
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAllByMedKitIds(medKitIds: Collection<UUID>): List<UUID> =
        repository.lockAllByMedKitIds(medKitIds)

    @Transactional(propagation = Propagation.MANDATORY)
    fun moveAll(sourceMedKitId: UUID, targetMedKitId: UUID) {
        repository.deletePlansWithoutTargetAccessByMedKit(sourceMedKitId, targetMedKitId)
        repository.moveAll(sourceMedKitId, targetMedKitId)
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun medKitId(userId: UUID, drugId: UUID): UUID =
        repository.findAccessible(userId, drugId)?.medKitId ?: throw DrugNotFound(drugId)

    @Transactional(propagation = Propagation.MANDATORY)
    fun create(command: CreateDrugCommand): DrugResult {
        val drug = Drug.create(
            CreateDrug(
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
        )
        repository.insert(drug)
        return DrugResult.from(drug, BigDecimal.ZERO)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun patch(userId: UUID, drugId: UUID, command: PatchDrugCommand): DrugResult {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        aggregate.drug.patch(
            DrugPatch(
                name = command.name,
                quantity = command.quantity,
                quantityUnit = command.quantityUnit,
                formType = command.formType,
                category = command.category,
                manufacturer = command.manufacturer,
                country = command.country,
                description = command.description
            )
        )
        repository.update(aggregate.drug)
        return DrugResult.from(aggregate.drug, aggregate.plans.totalPlannedAmount)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun consume(userId: UUID, drugId: UUID, amount: BigDecimal): DrugResult? {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        val decision = aggregate.drug.consume(amount, aggregate.plans)
        if (decision.exhausted) {
            repository.delete(drugId)
            return null
        }
        repository.update(aggregate.drug)
        repository.update(decision.changedPlans)
        return DrugResult.from(aggregate.drug, aggregate.plans.totalPlannedAmount)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun delete(userId: UUID, drugId: UUID) {
        repository.lockAccessible(userId, drugId) ?: throw DrugNotFound(drugId)
        check(repository.delete(drugId) == 1) { "Drug disappeared while locked" }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun move(
        userId: UUID,
        drugId: UUID,
        expectedSourceMedKitId: UUID,
        targetMedKitId: UUID,
        targetMemberIds: Set<UUID>
    ): DrugResult {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        if (aggregate.drug.medKitId != expectedSourceMedKitId) throw ConcurrentAggregateChange()
        if (aggregate.drug.medKitId == targetMedKitId) {
            return DrugResult.from(aggregate.drug, aggregate.plans.totalPlannedAmount)
        }
        aggregate.drug.moveTo(targetMedKitId, targetMemberIds, aggregate.plans)
        repository.deletePlansWithoutTargetAccess(drugId, targetMedKitId)
        check(repository.move(drugId, targetMedKitId) == 1) { "Drug disappeared while locked" }
        return DrugResult.from(aggregate.drug, aggregate.plans.totalPlannedAmount)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createPlan(userId: UUID, drugId: UUID, amount: BigDecimal): TreatmentPlanResult {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        val plan = aggregate.drug.createPlan(userId, amount, aggregate.plans)
        repository.insert(plan)
        return TreatmentPlanResult.from(plan)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun patchPlan(userId: UUID, drugId: UUID, amount: BigDecimal): TreatmentPlanResult {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        val plan = aggregate.drug.changePlan(userId, amount, aggregate.plans)
        repository.update(plan)
        return TreatmentPlanResult.from(plan)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deletePlan(userId: UUID, drugId: UUID) {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        aggregate.drug.deletePlan(userId, aggregate.plans)
        check(repository.deletePlan(userId, drugId) == 1) { "Treatment plan disappeared while locked" }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun applyIntake(userId: UUID, drugId: UUID, amount: BigDecimal): IntakeResult {
        val aggregate = repository.lockAggregate(userId, drugId) ?: throw DrugNotFound(drugId)
        val decision = aggregate.drug.applyIntake(userId, amount, aggregate.plans)
        if (decision.exhausted) {
            repository.delete(drugId)
            return IntakeResult(drug = null, plan = null)
        }

        repository.update(aggregate.drug)
        val plan = aggregate.plans.find(userId)
        if (plan == null) {
            check(repository.deletePlan(userId, drugId) == 1) { "Treatment plan disappeared while locked" }
        } else {
            repository.update(plan)
        }
        return IntakeResult(
            drug = DrugResult.from(aggregate.drug, aggregate.plans.totalPlannedAmount),
            plan = decision.plan?.let(TreatmentPlanResult::from)
        )
    }
}
