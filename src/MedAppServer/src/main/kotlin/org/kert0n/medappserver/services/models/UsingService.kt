package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.repository.UsingRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class UsingService(private val plans: UsingRepository) {

    @Transactional(readOnly = true)
    fun listForUser(userId: UUID): List<TreatmentPlanView> =
        plans.findAllByUserId(userId).map { it.toView() }

    @Transactional(readOnly = true)
    fun getForUser(userId: UUID, drugId: UUID): TreatmentPlanView =
        plans.findByUserIdAndDrugId(userId, drugId)?.toView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Treatment plan not found")
}
