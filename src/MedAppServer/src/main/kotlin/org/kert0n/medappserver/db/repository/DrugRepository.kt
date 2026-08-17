package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Строки препаратов. Наружу этот интерфейс не выходит — им пользуется только `DrugStore`.
 *
 * Читающие запросы забирают препарат вместе с его планами: сумму запланированного считает
 * домен по собственной коллекции, поэтому отдельного вычисляемого столбца больше нет.
 * Доступ проверяется соединением с членством, а не коллекцией участников внутри аптечки.
 *
 * Отдельной загрузки «под блокировку» здесь больше нет. Пока команды держал
 * `FOR UPDATE`, корень приходилось брать без планов — совмещать блокировку с внешним fetch
 * join нельзя, — и коллекция догружалась вторым запросом. С `@Version` блокировка не нужна, и
 * команда загружает агрегат тем же одним запросом, что и чтение.
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
     * препаратов — сотня загрузок. Здесь важнее постоянное число запросов, поэтому правило
     * переезда продублировано в SQL; парная половина — `Drug.moveTo`, и менять их надо вместе.
     *
     * Версию приходится двигать руками: массовый `UPDATE` идёт мимо dirty checking, и
     * `@Version` о нём не узнает. Не двинув её, мы бы позволили команде, собранной до
     * переезда, выполниться после него.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DrugData d SET d.medKit = :target, d.version = d.version + 1 WHERE d.medKit.id = :sourceMedKitId")
    fun moveAllToMedKit(@Param("sourceMedKitId") sourceMedKitId: UUID, @Param("target") target: MedKitData)

    /**
     * Продвигает версию тех препаратов аптечки, в которых у участника есть план.
     *
     * Пара к массовому удалению планов при выходе из аптечки: план — часть препарата, значит
     * его исчезновение меняет препарат, и версия обязана это отразить. Условие сужено до
     * препаратов с планом, чтобы выход одного участника не отменял чужие команды над
     * препаратами, которых он не касался. Вызывать до удаления — после него выбирать уже
     * нечего.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DrugData d SET d.version = d.version + 1
        WHERE d.medKit.id = :medKitId
          AND EXISTS (SELECT 1 FROM TreatmentPlanData p
                      WHERE p.planKey.drugId = d.id AND p.planKey.userId = :userId)
    """
    )
    fun bumpVersionsPlannedBy(@Param("medKitId") medKitId: UUID, @Param("userId") userId: UUID)
}
