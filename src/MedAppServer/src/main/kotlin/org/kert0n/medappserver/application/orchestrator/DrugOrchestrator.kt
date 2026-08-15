package org.kert0n.medappserver.application.orchestrator

import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.PatchDrugCommand
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.application.service.MedKitAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class DrugOrchestrator(
    private val drugService: DrugService,
    private val medKitAccessService: MedKitAccessService
) {
    @Transactional
    fun create(userId: UUID, command: CreateDrugCommand): DrugResult {
        medKitAccessService.lockAccessible(userId, listOf(command.medKitId))
        return drugService.create(command)
    }

    @Transactional
    fun patch(userId: UUID, drugId: UUID, command: PatchDrugCommand): DrugResult =
        drugService.patch(userId, drugId, command)

    @Transactional
    fun consume(userId: UUID, drugId: UUID, amount: BigDecimal): DrugResult? =
        drugService.consume(userId, drugId, amount)

    @Transactional
    fun delete(userId: UUID, drugId: UUID) = drugService.delete(userId, drugId)

    @Transactional
    fun move(userId: UUID, drugId: UUID, targetMedKitId: UUID): DrugResult {
        val source = drugServiceSourceMedKit(userId, drugId)
        medKitAccessService.lockAccessible(userId, setOf(source, targetMedKitId))
        val targetMembers = medKitAccessService.memberIds(targetMedKitId)
        return drugService.move(userId, drugId, source, targetMedKitId, targetMembers)
    }

    private fun drugServiceSourceMedKit(userId: UUID, drugId: UUID): UUID =
        drugService.medKitId(userId, drugId)
}
