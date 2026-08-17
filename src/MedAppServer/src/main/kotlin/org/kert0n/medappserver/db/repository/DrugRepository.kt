package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import java.util.*
import org.kert0n.medappserver.db.model.DrugData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Строки препаратов. Наружу этот интерфейс не выходит — им пользуется только `DrugStore`.
 *
 * Читающие запросы забирают препарат вместе с его планами: сумму запланированного считает
 * домен по собственной коллекции, поэтому отдельного вычисляемого столбца больше нет.
 * Доступ проверяется соединением с членством, а не коллекцией участников внутри аптечки.
 */
interface DrugRepository : JpaRepository<DrugData, UUID> {

    @Query(
        """
        SELECT DISTINCT d FROM DrugData d
        LEFT JOIN FETCH d.treatmentPlans
        WHERE d.id = :drugId
          AND EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKit.id AND m.membershipKey.userId = :userId)
    """
    )
    fun findAccessibleWithPlans(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): DrugData?

    @Query(
        """
        SELECT DISTINCT d FROM DrugData d
        LEFT JOIN FETCH d.treatmentPlans
        WHERE d.medKit.id = :medKitId
        ORDER BY d.name
    """
    )
    fun findAllInMedKitWithPlans(@Param("medKitId") medKitId: UUID): List<DrugData>

    /** Все препараты всех аптечек участника — одним запросом, для снимка. */
    @Query(
        """
        SELECT DISTINCT d FROM DrugData d
        LEFT JOIN FETCH d.treatmentPlans
        WHERE EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKit.id AND m.membershipKey.userId = :userId)
        ORDER BY d.name
    """
    )
    fun findAllAccessibleWithPlans(@Param("userId") userId: UUID): List<DrugData>

    /**
     * Загрузка под блокировкой строки.
     *
     * Планы здесь не забираются: совмещать `FOR UPDATE` с внешним fetch join нельзя, поэтому
     * коллекция подтягивается вторым запросом, когда команда её касается.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d FROM DrugData d
        WHERE d.id = :drugId
          AND EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKit.id AND m.membershipKey.userId = :userId)
    """
    )
    fun lockAccessible(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): DrugData?
}
