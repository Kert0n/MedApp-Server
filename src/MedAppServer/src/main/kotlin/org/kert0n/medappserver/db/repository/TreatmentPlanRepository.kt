package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface TreatmentPlanRepository : JpaRepository<TreatmentPlan, TreatmentPlanKey> {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanView(u.drug.id, u.plannedAmount)
        FROM TreatmentPlan u
        WHERE u.user.id = :userId
        ORDER BY u.drug.name
        """
    )
    fun findViewsOf(@Param("userId") userId: UUID): List<TreatmentPlanView>

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanView(u.drug.id, u.plannedAmount)
        FROM TreatmentPlan u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
        """
    )
    fun findView(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): TreatmentPlanView?


    // JPQL for explicit queries
    fun findAllByPlanKeyUserId(userId: UUID): List<TreatmentPlan>

    fun findAllByPlanKeyDrugId(@Param("drugId") drugId: UUID): List<TreatmentPlan>

    @Query(
        """
        SELECT u FROM TreatmentPlan u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
    """
    )
    fun findByUserIdAndDrugId(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): TreatmentPlan?

    // JPQL with fetch for eager loading
    @Query(
        """
        SELECT u FROM TreatmentPlan u
        JOIN FETCH u.drug
        WHERE u.user.id = :userId
    """
    )
    fun findAllByUserIdWithDrug(@Param("userId") userId: UUID): List<TreatmentPlan>

    /**
     * Массовое удаление планов участника внутри аптечки.
     *
     * Через агрегат этого не сделать: выход из аптечки касается всех её препаратов сразу, и
     * загружать каждый ради одного удаления незачем. Пока путь выхода живёт в оркестраторе и
     * ходит по коллекциям; на этот запрос он переведётся вместе с агрегатом MedKit.
     */
    @Modifying
    @Query("DELETE FROM TreatmentPlan u WHERE u.user.id = :userId AND u.drug.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(userId: UUID, medKitId: UUID)


}