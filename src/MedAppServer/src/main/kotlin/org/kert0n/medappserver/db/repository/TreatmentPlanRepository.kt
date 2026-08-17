package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.TreatmentPlanData
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

/** Строки планов лечения. Через этот интерфейс идут только те чтения, что не про один препарат. */
interface TreatmentPlanRepository : JpaRepository<TreatmentPlanData, TreatmentPlanKey> {

    @Query(
        """
        SELECT new org.kert0n.medappserver.domain.TreatmentPlan(
            p.planKey.userId, p.planKey.drugId, p.plannedAmount)
        FROM TreatmentPlanData p
        WHERE p.planKey.userId = :userId
        ORDER BY p.drugData.name
    """
    )
    fun findPlansOfUser(@Param("userId") userId: UUID): List<org.kert0n.medappserver.domain.TreatmentPlan>

    @Query(
        """
        SELECT new org.kert0n.medappserver.domain.TreatmentPlan(
            p.planKey.userId, p.planKey.drugId, p.plannedAmount)
        FROM TreatmentPlanData p
        WHERE p.planKey.userId = :userId AND p.planKey.drugId = :drugId
    """
    )
    fun findPlan(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): org.kert0n.medappserver.domain.TreatmentPlan?

    fun findAllByPlanKeyDrugId(drugId: UUID): List<TreatmentPlanData>

    /**
     * Массовое удаление планов участника внутри аптечки — путь выхода из неё.
     *
     * Через агрегаты этого не сделать: выход касается всех препаратов аптечки сразу, и
     * загружать каждый ради одного удаления незачем.
     */
    @Modifying
    @Query("DELETE FROM TreatmentPlanData p WHERE p.planKey.userId = :userId AND p.drugData.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(@Param("userId") userId: UUID, @Param("medKitId") medKitId: UUID)
}
