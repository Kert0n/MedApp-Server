package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {

    fun findAllByMedKitsId(@Param("medId") medId: UUID): Set<User>

    @Query(
        """
        SELECT p.user FROM Using p
        WHERE p.drug.id = :drugId
    """
    )
    fun findByUsingsDrugId(@Param("drugId") drugId: UUID): Set<User>

    @Query(
        """
        SELECT u FROM User u
        LEFT JOIN FETCH u.medKits
        WHERE u.id = :id
    """
    )
    fun findByIdWithMedKits(@Param("id") id: UUID): User?
}
