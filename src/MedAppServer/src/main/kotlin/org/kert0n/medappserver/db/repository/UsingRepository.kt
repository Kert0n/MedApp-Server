package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UsingRepository : JpaRepository<TreatmentPlan, TreatmentPlanKey> {

    // JPQL for explicit queries
    fun findAllByKeyUserId(userId: UUID): List<TreatmentPlan>

    fun findAllByKeyDrugId(@Param("drugId") drugId: UUID): List<TreatmentPlan>

    @Query("SELECT plan FROM TreatmentPlan plan WHERE plan.key.userId = :userId")
    fun findAllByUsingKeyUserId(userId: UUID): List<TreatmentPlan>

    @Query("SELECT plan FROM TreatmentPlan plan WHERE plan.key.drugId = :drugId")
    fun findAllByUsingKeyDrugId(drugId: UUID): List<TreatmentPlan>

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

    @Modifying
    @Query("DELETE FROM TreatmentPlan u WHERE u.user.id = :userId AND u.drug.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(userId: UUID, medKitId: UUID)


}
