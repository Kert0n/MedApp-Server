package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanView
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

/**
 * Чтения планов лечения.
 *
 * Менять планы отсюда нельзя: план — часть агрегата `Drug`, и все его изменения идут через
 * [DrugService], где вместе с планом виден остаток препарата. Здесь остаётся только выборка,
 * потому что «мои планы по всем препаратам» — это не операция над одним препаратом, и
 * приводить такой запрос к корню агрегата означало бы грузить каждый препарат целиком.
 */
@Service
class TreatmentPlanService(
    private val treatmentPlanRepository: TreatmentPlanRepository,
    val logger: Logger = LoggerFactory.getLogger(TreatmentPlanService::class.java)
) {

    @Transactional(readOnly = true)
    fun viewsOf(userId: UUID): List<TreatmentPlanView> {
        logger.debug("Reading treatment plans of user {}", userId)
        return treatmentPlanRepository.findViewsOf(userId)
    }

    /** План для показа или `null`, если его нет. */
    @Transactional(readOnly = true)
    fun findView(userId: UUID, drugId: UUID): TreatmentPlanView? =
        treatmentPlanRepository.findView(userId, drugId)

    /** План для показа или 404. */
    @Transactional(readOnly = true)
    fun requireView(userId: UUID, drugId: UUID): TreatmentPlanView =
        findView(userId, drugId) ?: throw noSuchPlan()

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<TreatmentPlan> {
        logger.debug("Finding all treatment plans for user: {}", userId)
        return treatmentPlanRepository.findAllByPlanKeyUserId(userId)
    }

    @Transactional(readOnly = true)
    fun findAllByDrug(drugId: UUID): List<TreatmentPlan> {
        logger.debug("Finding all treatment plans for drug: {}", drugId)
        return treatmentPlanRepository.findAllByPlanKeyDrugId(drugId)
    }

    @Transactional(readOnly = true)
    fun findPlan(userId: UUID, drugId: UUID): TreatmentPlan? =
        treatmentPlanRepository.findByUserIdAndDrugId(userId, drugId)

    @Transactional(readOnly = true)
    fun requirePlan(userId: UUID, drugId: UUID): TreatmentPlan {
        logger.debug("Finding treatment plan for user {} and drug {}", userId, drugId)
        return findPlan(userId, drugId) ?: throw noSuchPlan()
    }

    private fun noSuchPlan() = ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such treatment plan")
}
