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

    // ── Чтение: проекции ─────────────────────────────────────────────────────────
    //
    // Сумма планов берётся как Drug.totalPlannedAmount, то есть через ту же @Formula, что и
    // при загрузке сущности. Повторять здесь SUM нельзя: определений станет по одному на
    // каждый запрос плюс формула, и при изменении смысла «запланировано» их пришлось бы
    // править синхронно.

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.DrugView(
            d.id, d.name, d.quantity, d.totalPlannedAmount,
            d.quantityUnit, d.formType, d.category, d.manufacturer, d.country, d.description, mk.id)
        FROM Drug d
        JOIN d.medKit mk
        WHERE d.id = :drugId AND EXISTS (SELECT 1 FROM MedKit m JOIN m.users mu WHERE m = mk AND mu.id = :userId)
        """
    )
    fun findViewAccessible(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): DrugView?

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.DrugView(
            d.id, d.name, d.quantity, d.totalPlannedAmount,
            d.quantityUnit, d.formType, d.category, d.manufacturer, d.country, d.description, mk.id)
        FROM Drug d
        JOIN d.medKit mk
        WHERE mk.id = :medKitId
        ORDER BY d.name
        """
    )
    fun findViewsByMedKit(@Param("medKitId") medKitId: UUID): List<DrugView>

    /**
     * Все препараты во всех аптечках пользователя — одним запросом.
     *
     * Снимок раньше собирался по аптечке за раз, то есть числом запросов, растущим вместе с
     * числом аптечек.
     */
    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.DrugView(
            d.id, d.name, d.quantity, d.totalPlannedAmount,
            d.quantityUnit, d.formType, d.category, d.manufacturer, d.country, d.description, mk.id)
        FROM Drug d
        JOIN d.medKit mk
        WHERE EXISTS (SELECT 1 FROM MedKit m JOIN m.users mu WHERE m = mk AND mu.id = :userId)
        ORDER BY d.name
        """
    )
    fun findViewsAccessibleTo(@Param("userId") userId: UUID): List<DrugView>

    // ── Команды: сущность целиком ────────────────────────────────────────────────

    fun findAllByMedKitId(@Param("medKitId") medKitId: UUID): List<Drug>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d FROM Drug d
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun lockAccessible(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    @Query(
        """
        SELECT d FROM Drug d
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun findAccessible(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    /** Команде выхода из аптечки нужны сами планы: она их удаляет через коллекцию. */
    @EntityGraph(attributePaths = ["treatmentPlans"])
    fun findAllWithTreatmentPlansByMedKitId(medKitId: UUID): List<Drug>
}
