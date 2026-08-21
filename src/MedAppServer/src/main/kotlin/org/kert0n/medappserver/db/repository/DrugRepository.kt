package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.DrugData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Строки упаковок. Наружу не выходит — им пользуется только `DrugStore`.
 *
 * Чтения забирают одну упаковку: броней она не держит. Доступ проверяется соединением с
 * членством.
 */
interface DrugRepository : JpaRepository<DrugData, UUID> {

    @Query(
        """
        SELECT d FROM DrugData d
        WHERE d.id = :drugId
          AND EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKitId AND m.membershipKey.userId = :userId)
    """
    )
    fun find(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): DrugData?

    /** Без проверки доступа: её делает вызывающий, когда она нужна. */
    @Query(
        """
        SELECT d FROM DrugData d
        WHERE d.id = :drugId
    """
    )
    fun findFullById(@Param("drugId") drugId: UUID): DrugData?

    @Query(
        """
        SELECT d FROM DrugData d
        WHERE d.medKitId = :medKitId
          AND EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKitId AND m.membershipKey.userId = :userId)
        ORDER BY d.name
    """
    )
    fun findAllInMedKit(@Param("medKitId") medKitId: UUID, @Param("userId") userId: UUID): List<DrugData>

    /** Все упаковки всех аптечек участника — одним запросом. */
    @Query(
        """
        SELECT d FROM DrugData d
        WHERE EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKitId AND m.membershipKey.userId = :userId)
        ORDER BY d.name
    """
    )
    fun findAllOfUser(@Param("userId") userId: UUID): List<DrugData>

    /**
     * Перевод всех упаковок аптечки в другую — одним запросом.
     *
     * Поштучный переезд через агрегат честнее по слоям, но стоит команды на пачку: сотня
     * пачек — сотня загрузок. Здесь важнее постоянное число запросов.
     */
    @Modifying
    @Query("UPDATE DrugData d SET d.medKitId = :targetMedKitId WHERE d.medKitId = :sourceMedKitId")
    fun moveAllToMedKit(
        @Param("sourceMedKitId") sourceMedKitId: UUID,
        @Param("targetMedKitId") targetMedKitId: UUID
    )
}
