package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.ReservationData
import org.kert0n.medappserver.db.model.ReservationKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Строки броней.
 *
 * Каждое чтение забирает упаковку и её единицу измерения соединением, и это не оптимизация, а
 * необходимость: величина брони измеряется в единице своей пачки, и без неё бронь не собрать.
 * `EAGER`-связь сама по себе этого не даёт — HQL догружал бы упаковку отдельным запросом на
 * каждую строку.
 */
interface ReservationRepository : JpaRepository<ReservationData, ReservationKey> {

    @Query(
        """
        SELECT r FROM ReservationData r
        JOIN FETCH r.drugData d
        JOIN FETCH d.quantityUnit
        WHERE r.reservationKey.userId = :userId
        ORDER BY d.name
    """
    )
    fun findAllOfUser(@Param("userId") userId: UUID): List<ReservationData>

    @Query(
        """
        SELECT r FROM ReservationData r
        JOIN FETCH r.drugData d
        JOIN FETCH d.quantityUnit
        WHERE r.reservationKey.userId = :userId AND r.reservationKey.drugId = :drugId
    """
    )
    fun findOne(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): ReservationData?

    /** Все брони на перечисленные упаковки — чтобы показать, сколько на пачку заявлено. */
    @Query(
        """
        SELECT r FROM ReservationData r
        JOIN FETCH r.drugData d
        JOIN FETCH d.quantityUnit
        WHERE r.reservationKey.drugId IN :drugIds
    """
    )
    fun findAllOfDrugs(@Param("drugIds") drugIds: Collection<UUID>): List<ReservationData>

    /**
     * Брони участника во всех упаковках аптечки — путь выхода из неё.
     *
     * Через агрегаты этого не сделать: выход касается всех пачек аптечки сразу, и загружать
     * каждую ради одного удаления незачем.
     */
    @Modifying
    @Query(
        "DELETE FROM ReservationData r WHERE r.reservationKey.userId = :userId AND r.drugData.medKit.id = :medKitId"
    )
    fun deleteOfUserInMedKit(@Param("userId") userId: UUID, @Param("medKitId") medKitId: UUID)

    /**
     * Брони всех, кто к аптечке доступа не имеет, — одним запросом.
     *
     * Пара к массовому переезду упаковок: бронь не переживает утрату доступа к пачке.
     */
    @Modifying
    @Query(
        """
        DELETE FROM ReservationData r
        WHERE r.drugData.medKit.id = :medKitId AND r.reservationKey.userId NOT IN :allowedUserIds
    """
    )
    fun deleteInMedKitExcept(
        @Param("medKitId") medKitId: UUID,
        @Param("allowedUserIds") allowedUserIds: Collection<UUID>
    )

    /**
     * Все брони упаковки — когда пачку уничтожают.
     *
     * Внешний ключ с каскадом уносит их и сам, но Hibernate об этом не знает: уже загруженные
     * строки остались бы ссылаться на удалённую пачку и уронили бы ближайший flush. Это
     * persistence-половина того же правила, а не решение агрегата.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ReservationData r WHERE r.reservationKey.drugId = :drugId")
    fun deleteOfDrug(@Param("drugId") drugId: UUID)

    /** То же для одной упаковки: она переехала, и кто-то её больше не видит. */
    @Modifying
    @Query(
        """
        DELETE FROM ReservationData r
        WHERE r.reservationKey.drugId = :drugId AND r.reservationKey.userId NOT IN :allowedUserIds
    """
    )
    fun deleteOfDrugExcept(
        @Param("drugId") drugId: UUID,
        @Param("allowedUserIds") allowedUserIds: Collection<UUID>
    )
}
