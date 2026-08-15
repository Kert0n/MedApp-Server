package org.kert0n.medappserver.application.orchestrator

import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.kert0n.medappserver.application.service.DrugService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class TreatmentPlanOrchestrator(
    private val drugService: DrugService
) {
    @Transactional
    fun create(userId: UUID, command: CreateTreatmentPlanCommand): TreatmentPlanResult =
        drugService.createPlan(userId, command.drugId, command.plannedAmount)

    @Transactional
    fun patch(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlanResult =
        drugService.patchPlan(userId, drugId, plannedAmount)

    @Transactional
    fun delete(userId: UUID, drugId: UUID) = drugService.deletePlan(userId, drugId)
}
