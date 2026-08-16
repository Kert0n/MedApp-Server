package org.kert0n.medappserver.db.repository


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
        JOIN mk.users u
        WHERE mk.id = :id AND u.id = :userId
    """
    )
    fun findByIdAndUserId(@Param("id") id: UUID, @Param("userId") userId: UUID): MedKit?

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.MedKitSummary(
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
    fun findMedKitSummariesByUserId(@Param("userId") userId: UUID): Set<MedKitSummary>

    @EntityGraph(attributePaths = ["users", "drugs", "drugs.treatmentPlans"])
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

}

