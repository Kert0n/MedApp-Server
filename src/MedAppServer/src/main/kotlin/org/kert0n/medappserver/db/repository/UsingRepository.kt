package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UsingRepository : JpaRepository<Using, UsingKey> {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanView(u.drug.id, u.plannedAmount)
        FROM Using u
        WHERE u.user.id = :userId
        ORDER BY u.drug.name
        """
    )
    fun findViewsOf(@Param("userId") userId: UUID): List<TreatmentPlanView>

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanView(u.drug.id, u.plannedAmount)
        FROM Using u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
        """
    )
    fun findView(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): TreatmentPlanView?


    // JPQL for explicit queries
    fun findAllByUsingKeyUserId(userId: UUID): List<Using>

    fun findAllByUsingKeyDrugId(@Param("drugId") drugId: UUID): List<Using>

    @Query(
        """
        SELECT u FROM Using u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
    """
    )
    fun findByUserIdAndDrugId(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): Using?

    // JPQL with fetch for eager loading
    @Query(
        """
        SELECT u FROM Using u
        JOIN FETCH u.drug
        WHERE u.user.id = :userId
    """
    )
    fun findAllByUserIdWithDrug(@Param("userId") userId: UUID): List<Using>

    @Modifying
    @Query("DELETE FROM Using u WHERE u.user.id = :userId AND u.drug.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(userId: UUID, medKitId: UUID)


}