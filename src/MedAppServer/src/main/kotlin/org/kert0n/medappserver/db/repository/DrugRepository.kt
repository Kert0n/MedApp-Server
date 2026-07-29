package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import org.kert0n.medappserver.db.model.Drug
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface DrugRepository : JpaRepository<Drug, UUID> {

    fun findAllByMedKitId(@Param("medKitId") medKitId: UUID): List<Drug>

    fun findAllByMedKitIdIn(medKitIds: Collection<UUID>): List<Drug>

    @Query(
        """
        SELECT d FROM Drug d
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
        """
    )
    fun findAccessible(drugId: UUID, userId: UUID): Drug?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d FROM Drug d
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
        """
    )
    fun findAccessibleForUpdate(drugId: UUID, userId: UUID): Drug?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Drug d WHERE d.medKit.id = :medKitId ORDER BY d.id")
    fun lockAllByMedKitIdOrderById(medKitId: UUID): List<Drug>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Drug d SET d.medKit.id = :targetMedKitId WHERE d.id = :drugId")
    fun moveToMedKit(drugId: UUID, targetMedKitId: UUID): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Drug d SET d.medKit.id = :targetMedKitId WHERE d.medKit.id = :sourceMedKitId")
    fun moveAllToMedKit(sourceMedKitId: UUID, targetMedKitId: UUID): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Drug d WHERE d.id = :drugId")
    fun deleteLockedById(drugId: UUID): Int

}
