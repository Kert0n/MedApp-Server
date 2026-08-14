package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UsingRepository : JpaRepository<Using, UsingKey> {

    @Query("SELECT u FROM Using u WHERE u.usingKey.userId = :userId")
    fun findAllByUserId(userId: UUID): List<Using>

    @Query("SELECT u FROM Using u WHERE u.usingKey.drugId = :drugId ORDER BY u.usingKey.userId")
    fun findAllByDrugId(drugId: UUID): List<Using>

    @Query(
        """
        SELECT u FROM Using u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
    """
    )
    fun findByUserIdAndDrugId(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): Using?

    /**
     * Удаляет планы участника в аптечке одним оператором.
     * Контекст не очищается: вызывающая команда продолжает менять membership.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            DELETE FROM usings
            WHERE user_id = :userId
              AND drug_id IN (
                  SELECT id FROM user_drugs WHERE med_kit_id = :medKitId
              )
        """,
        nativeQuery = true
    )
    fun deleteByUserIdAndMedKitId(userId: UUID, medKitId: UUID)

    /**
     * При переносе удаляет планы пользователей без доступа к цели.
     * Список целевых участников непуст; очистка исключает устаревшие managed Using после bulk.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            DELETE FROM usings
            WHERE drug_id IN (
                SELECT id FROM user_drugs WHERE med_kit_id = :medKitId
            )
              AND user_id NOT IN (:userIds)
        """,
        nativeQuery = true
    )
    fun deleteByMedKitIdAndUserIdNotIn(medKitId: UUID, userIds: Collection<UUID>)

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            DELETE FROM usings
            WHERE drug_id = :drugId
              AND user_id NOT IN (:userIds)
        """,
        nativeQuery = true
    )
    fun deleteByDrugIdAndUserIdNotIn(drugId: UUID, userIds: Collection<UUID>): Int
}
