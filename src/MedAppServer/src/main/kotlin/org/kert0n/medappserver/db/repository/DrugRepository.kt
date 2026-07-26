@file:Suppress("FunctionName")

package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import org.kert0n.medappserver.db.model.Drug
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface DrugRepository : JpaRepository<Drug, UUID> {

    fun findAllByMedKitId(@Param("medKitId") medKitId: UUID): List<Drug>

    @Query(
        """
        SELECT DISTINCT d FROM Drug d 
        JOIN d.usings u
        WHERE u.user.id = :userId
    """
    )
    fun findByUsingsUserId(@Param("userId") userId: UUID): List<Drug>

    @EntityGraph(attributePaths = ["usings"])
    @Query(
        """
    SELECT d FROM Drug d 
    JOIN d.medKit mk
    JOIN mk.users u
    WHERE d.id = :drugId AND u.id = :userId
"""
    )
    fun findByIdAndMedKitUsersIdWithUsings(drugId: UUID, userId: UUID): Drug?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d FROM Drug d 
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun findByIdAndMedKitUsersIdForUpdate(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    @Query(
        """
        SELECT d FROM Drug d 
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun findByIdAndMedKitUsersId(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    // In DrugRepository
    @EntityGraph(attributePaths = ["usings"])
    fun findAllWithUsingsByMedKitId(medKitId: UUID): List<Drug>

//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @EntityGraph(attributePaths = ["usings"])
//    @Query("SELECT d FROM Drug d LEFT JOIN FETCH d.usings WHERE d.id = :id")
//    fun findWithUsingsByIdForUpdate(drugId: UUID): Drug?


//    @Query("SELECT COALESCE(SUM(u.plannedAmount), 0.0) FROM Using u WHERE u.drug.id = :drugId")
//    fun sumPlannedAmount(@Param("drugId") drugId: UUID): Double


}