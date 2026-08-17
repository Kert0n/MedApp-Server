package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import java.util.*
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
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

    /** Препарат целиком, без проверки доступа: её делает вызывающий, когда она нужна. */
    @Query(
        """
        SELECT DISTINCT d FROM DrugData d
        LEFT JOIN FETCH d.treatmentPlans
        WHERE d.id = :drugId
    """
    )
    fun findByIdWithPlans(@Param("drugId") drugId: UUID): DrugData?

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
     * Перевод всех препаратов аптечки в другую — одним запросом.
     *
     * Поштучный переезд через агрегат честнее по слоям, но стоит команды на препарат: сотня
     * препаратов — сотня загрузок с блокировкой. Здесь важнее постоянное число запросов,
     * поэтому правило переезда продублировано в SQL; парная половина — `Drug.moveTo`, и
     * менять их надо вместе.
     */
    @Modifying
    @Query("UPDATE DrugData d SET d.medKit = :target WHERE d.medKit.id = :sourceMedKitId")
    fun moveAllToMedKit(@Param("sourceMedKitId") sourceMedKitId: UUID, @Param("target") target: MedKitData)

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
