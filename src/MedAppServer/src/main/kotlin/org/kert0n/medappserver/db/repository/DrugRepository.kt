package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
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
                      WHERE m.membershipKey.medKitId = d.medKit.id AND m.membershipKey.userId = :userId)
    """
    )
    fun findAccessible(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): DrugData?

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
        WHERE d.medKit.id = :medKitId
        ORDER BY d.name
    """
    )
    fun findAllInMedKit(@Param("medKitId") medKitId: UUID): List<DrugData>

    /** Все упаковки всех аптечек участника — одним запросом. */
    @Query(
        """
        SELECT d FROM DrugData d
        WHERE EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = d.medKit.id AND m.membershipKey.userId = :userId)
        ORDER BY d.name
    """
    )
    fun findAllAccessible(@Param("userId") userId: UUID): List<DrugData>

    /**
     * Перевод всех упаковок аптечки в другую — одним запросом.
     *
     * Поштучный переезд через агрегат честнее по слоям, но стоит команды на пачку: сотня
     * пачек — сотня загрузок. Здесь важнее постоянное число запросов.
     *
     * Версия двигается частью самого `UPDATE` — единственное место, где это допустимо:
     * сущностей в памяти нет, и распорядиться версией Hibernate не может. `flushAutomatically`
     * отправляет незаписанные изменения до оператора, `clearAutomatically` выселяет ставшие
     * несвежими строки после него, иначе в контексте остались бы упаковки со старой версией и
     * старой аптечкой.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE DrugData d
        SET d.medKit = :target, d.version = d.version + 1
        WHERE d.medKit.id = :sourceMedKitId
    """
    )
    fun moveAllToMedKit(@Param("sourceMedKitId") sourceMedKitId: UUID, @Param("target") target: MedKitData)
}
