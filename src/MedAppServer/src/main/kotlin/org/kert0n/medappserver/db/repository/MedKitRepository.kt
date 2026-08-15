package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.controller.MedKitSummaryDTO
import org.kert0n.medappserver.db.model.MedKit
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface MedKitRepository : JpaRepository<MedKit, UUID> {

    @EntityGraph(attributePaths = ["drugs"])
    @Query(
        """
        SELECT mk FROM MedKit mk
        JOIN mk.users u
        WHERE u.id = :userId
    """
    )
    fun findByUserId(@Param("userId") userId: UUID): List<MedKit>

    @Query(
        """
        SELECT mk FROM MedKit mk
        LEFT JOIN FETCH mk.drugs
        WHERE mk.id = :id
    """
    )
    fun findByIdWithDrugs(@Param("id") id: UUID): MedKit?

    @Query(
        """
        SELECT DISTINCT mk FROM MedKit mk
        LEFT JOIN FETCH mk.users
        WHERE mk.id = :id
    """
    )
    fun findByIdWithUsers(@Param("id") id: UUID): MedKit?

    @Query(
        """
        SELECT mk FROM MedKit mk
        JOIN mk.users u
        WHERE mk.id = :id AND u.id = :userId
    """
    )
    fun findByIdAndUserId(@Param("id") id: UUID, @Param("userId") userId: UUID): MedKit?

    @Query(
        """
        SELECT new org.kert0n.medappserver.controller.MedKitSummaryDTO(
        mk.id, 
        COUNT(DISTINCT u), 
        COUNT(DISTINCT d)
    )
    FROM MedKit mk
    LEFT JOIN mk.users u
    LEFT JOIN mk.drugs d
    WHERE mk.id IN (SELECT m.id FROM MedKit m JOIN m.users us WHERE us.id = :userId)
    GROUP BY mk.id
    """
    )
    fun findMedKitSummariesByUserId(@Param("userId") userId: UUID): Set<MedKitSummaryDTO>

    @EntityGraph(attributePaths = ["users", "drugs", "drugs.usings"])
    @Query(
        """
        SELECT mk FROM MedKit mk
        JOIN mk.users u
        WHERE mk.id = :id AND u.id = :userId
    """
    )
    fun findByIdAndUserIdForDeletion(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID
    ): MedKit?

    @EntityGraph(attributePaths = ["users"])
    @Query(
        """
    SELECT mk FROM MedKit mk
    JOIN mk.users u
    WHERE mk.id = :id AND u.id = :userId
"""
    )
    fun findByIdAndUsersIdWithUsers(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID
    ): MedKit?

    @Modifying
    @Query("UPDATE Drug d SET d.medKit.id = :targetMedKitId WHERE d.medKit.id = :homeMedkit")
    fun reassignMedKit(homeMedkit: UUID, targetMedKitId: UUID)
}
