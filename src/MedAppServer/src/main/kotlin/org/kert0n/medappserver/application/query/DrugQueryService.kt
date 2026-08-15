package org.kert0n.medappserver.application.query

import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.kert0n.medappserver.domain.error.DrugNotFound
import org.kert0n.medappserver.domain.error.TreatmentPlanNotFound
import org.kert0n.medappserver.persistence.repository.DrugProjectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class DrugQueryService(
    private val repository: DrugProjectionRepository
) {
    fun getAccessible(userId: UUID, drugId: UUID): DrugResult =
        repository.findAccessible(userId, drugId) ?: throw DrugNotFound(drugId)
}

@Service
@Transactional(readOnly = true)
class TreatmentPlanQueryService(
    private val repository: DrugProjectionRepository
) {
    fun listForUser(userId: UUID): List<TreatmentPlanResult> = repository.plansForUser(userId)

    fun getForUser(userId: UUID, drugId: UUID): TreatmentPlanResult =
        repository.planForUser(userId, drugId) ?: throw TreatmentPlanNotFound(userId, drugId)
}
