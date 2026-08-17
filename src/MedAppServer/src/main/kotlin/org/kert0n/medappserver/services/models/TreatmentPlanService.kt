package org.kert0n.medappserver.services.models

import java.util.UUID
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.NoSuchTreatmentPlan
import org.kert0n.medappserver.domain.TreatmentPlanEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Чтения планов лечения.
 *
 * Менять планы отсюда нельзя: план — часть агрегата препарата, и все его изменения идут
 * через [DrugService], где вместе с планом виден остаток. Здесь остаётся выборка, потому что
 * «мои планы по всем препаратам» — не операция над одним препаратом, и сводить такой запрос
 * к корню значило бы поднимать каждый препарат целиком.
 */
@Service
class TreatmentPlanService(private val drugs: DrugStore) {

    private val logger = LoggerFactory.getLogger(TreatmentPlanService::class.java)

    @Transactional(readOnly = true)
    fun plansOf(userId: UUID): List<TreatmentPlanEntry> {
        logger.debug("Reading treatment plans of user {}", userId)
        return drugs.findPlansOf(userId)
    }

    /** План или `null`, если его нет. */
    @Transactional(readOnly = true)
    fun findPlan(userId: UUID, drugId: UUID): TreatmentPlanEntry? = drugs.findPlan(userId, drugId)

    /** План или 404. */
    @Transactional(readOnly = true)
    fun requirePlan(userId: UUID, drugId: UUID): TreatmentPlanEntry =
        findPlan(userId, drugId) ?: throw NoSuchTreatmentPlan()
}
