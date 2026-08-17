package org.kert0n.medappserver.db.repository

import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.TreatmentPlanData
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Строка плана вместе с единицей измерения своего препарата.
 *
 * Доменный план несёт величину, а величина без единицы не существует; единица же лежит у
 * препарата. Тянуть ради неё сам препарат незачем, поэтому запрос забирает её соединением, а
 * доменный тип собирается в хранилище. Наружу этот тип не выходит: им пользуется только `DrugStore`, а сервисы
 * получают уже собранный доменный план.
 */
data class TreatmentPlanRow(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal,
    val unitId: UUID,
    val unitName: String
)

/** Строки планов лечения. Через этот интерфейс идут только те чтения, что не про один препарат. */
interface TreatmentPlanRepository : JpaRepository<TreatmentPlanData, TreatmentPlanKey> {

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanRow(
            p.planKey.userId, p.planKey.drugId, p.plannedAmount, u.id, u.name)
        FROM TreatmentPlanData p
        JOIN p.drugData d
        JOIN d.quantityUnit u
        WHERE p.planKey.userId = :userId
        ORDER BY d.name
    """
    )
    fun findPlansOfUser(@Param("userId") userId: UUID): List<TreatmentPlanRow>

    @Query(
        """
        SELECT new org.kert0n.medappserver.db.repository.TreatmentPlanRow(
            p.planKey.userId, p.planKey.drugId, p.plannedAmount, u.id, u.name)
        FROM TreatmentPlanData p
        JOIN p.drugData d
        JOIN d.quantityUnit u
        WHERE p.planKey.userId = :userId AND p.planKey.drugId = :drugId
    """
    )
    fun findPlan(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): TreatmentPlanRow?

    /**
     * Массовое удаление планов участника внутри аптечки — путь выхода из неё.
     *
     * Через агрегаты этого не сделать: выход касается всех препаратов аптечки сразу, и
     * загружать каждый ради одного удаления незачем.
     */
    @Modifying
    @Query("DELETE FROM TreatmentPlanData p WHERE p.planKey.userId = :userId AND p.drugData.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(@Param("userId") userId: UUID, @Param("medKitId") medKitId: UUID)

    /**
     * Планы всех, кто к аптечке доступа не имеет, — одним запросом.
     *
     * Пара к массовому переезду препаратов: план не переживает утрату доступа, и здесь это
     * правило записано вторым — первым его знает `Drug.moveTo`. Дублирование сознательное,
     * ради постоянного числа запросов при удалении аптечки с переносом.
     */
    @Modifying
    @Query(
        """
        DELETE FROM TreatmentPlanData p
        WHERE p.drugData.medKit.id = :medKitId AND p.planKey.userId NOT IN :allowedUserIds
    """
    )
    fun deleteInMedKitExcept(
        @Param("medKitId") medKitId: UUID,
        @Param("allowedUserIds") allowedUserIds: Collection<UUID>
    )
}
