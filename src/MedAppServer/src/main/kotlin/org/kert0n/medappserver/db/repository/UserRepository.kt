package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {

    fun findAllByMedKitsId(@Param("medId") medId: UUID): Set<User>

    /**
     * Участники, у которых есть план на препарат.
     *
     * Соединение явное: коллекции планов у пользователя нет, потому что план принадлежит
     * агрегату Drug, а не пользователю.
     */
    @Query(
        """
        SELECT u FROM User u
        JOIN TreatmentPlan tp ON tp.user = u
        WHERE tp.drug.id = :drugId
    """
    )
    fun findByTreatmentPlansDrugId(@Param("drugId") drugId: UUID): Set<User>

    @Query(
        """
        SELECT u FROM User u
        LEFT JOIN FETCH u.medKits
        WHERE u.id = :id
    """
    )
    fun findByIdWithMedKits(@Param("id") id: UUID): User?
}