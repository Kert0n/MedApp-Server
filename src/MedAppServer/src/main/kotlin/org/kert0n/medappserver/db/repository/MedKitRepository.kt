package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import org.kert0n.medappserver.db.model.MedKit
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface MedKitRepository : JpaRepository<MedKit, UUID> {

    @Query(
        """
        SELECT mk FROM MedKit mk
        JOIN mk.users u
        WHERE mk.id = :medKitId AND u.id = :userId
        """
    )
    fun findAccessible(medKitId: UUID, userId: UUID): MedKit?

    @EntityGraph(attributePaths = ["users"])
    @Query(
        """
        SELECT mk FROM MedKit mk
        JOIN mk.users u
        WHERE mk.id = :medKitId AND u.id = :userId
        """
    )
    fun findAccessibleWithUsers(medKitId: UUID, userId: UUID): MedKit?

    @Query("SELECT u.id FROM MedKit mk JOIN mk.users u WHERE mk.id = :medKitId")
    fun findMemberIds(medKitId: UUID): Set<UUID>

    @Query("SELECT COUNT(u) FROM MedKit mk JOIN mk.users u WHERE mk.id = :medKitId")
    fun countMembers(medKitId: UUID): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "DELETE FROM user_med_kits WHERE med_kit_id = :medKitId AND user_id = :userId",
        nativeQuery = true
    )
    fun deleteMembership(medKitId: UUID, userId: UUID): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT mk FROM MedKit mk
        WHERE mk.id = :medKitId
          AND EXISTS (
            SELECT member.id FROM User member
            JOIN member.medKits memberKit
            WHERE member.id = :userId AND memberKit.id = mk.id
          )
        """
    )
    fun findAccessibleForUpdate(medKitId: UUID, userId: UUID): MedKit?

    @Query("SELECT mk.id FROM MedKit mk JOIN mk.users u WHERE u.id = :userId ORDER BY mk.id")
    fun findIdsByUserId(userId: UUID): List<UUID>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM MedKit mk WHERE mk.id = :medKitId")
    fun deleteLockedById(medKitId: UUID): Int

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.MedKitSummary(
        mk.id, 
        COUNT(DISTINCT u),
        (SELECT COUNT(d) FROM Drug d WHERE d.medKit.id = mk.id)
    )
    FROM MedKit mk
    LEFT JOIN mk.users u
    WHERE mk.id IN (SELECT m.id FROM MedKit m JOIN m.users us WHERE us.id = :userId)
    GROUP BY mk.id
    """
    )
    fun findMedKitSummariesByUserId(@Param("userId") userId: UUID): Set<MedKitSummary>

}
