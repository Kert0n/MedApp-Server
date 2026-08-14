package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.MedKit
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
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

    /**
     * Переносит все препараты аптечки в другую — одним оператором.
     *
     * Альтернатива — пройти по `medKit.drugs` и каждому переставить ссылку: выборка всех
     * препаратов плюс UPDATE на каждый. Здесь работу делает БД.
     *
     * `clearAutomatically` здесь обязателен, и это не перестраховка. Загруженная ранее
     * `medKit.drugs` после bulk показывает препараты, которых в аптечке уже нет, а удаление
     * аптечки идёт каскадом по этой коллекции — то есть каскад сносит только что
     * перенесённое. Загрузить аптечку «без графа» внутри метода недостаточно: коллекцию мог
     * инициализировать любой запрос выше по транзакции (например `findByUserId`, у которого
     * граф с `drugs`), и Hibernate вернёт тот же управляемый экземпляр. Проверено падением
     * теста миграции: препараты переезжали, а затем удалялись каскадом.
     *
     * Следствие для вызывающего: всё, что нужно после этого оператора, читать заново.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Drug d SET d.medKit.id = :targetMedKitId WHERE d.medKit.id = :homeMedkit")
    fun reassignMedKit(homeMedkit: UUID, targetMedKitId: UUID)
}
