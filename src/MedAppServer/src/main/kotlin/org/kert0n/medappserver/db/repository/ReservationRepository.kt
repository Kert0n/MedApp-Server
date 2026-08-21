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
 * Упаковка и её единица забираются соединением: величина брони измеряется в единице своей пачки,
 * и без неё бронь не собрать. `EAGER` тут не помог бы — HQL догружал бы пачку на каждую строку.
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

    @Query(
        """
        SELECT r FROM ReservationData r
        JOIN FETCH r.drugData d
        JOIN FETCH d.quantityUnit
        WHERE r.reservationKey.drugId IN :drugIds
          AND EXISTS (SELECT 1 FROM MedKitMembershipData m
                      WHERE m.membershipKey.medKitId = r.medKitId AND m.membershipKey.userId = :userId)
    """
    )
    fun findAllOfDrugs(
        @Param("drugIds") drugIds: Collection<UUID>,
        @Param("userId") userId: UUID
    ): List<ReservationData>

    /** Пара к массовому переезду упаковок: бронь не переживает утрату доступа к пачке. */
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
     * Все брони уничтожаемой пачки.
     *
     * Каскад внешнего ключа сделал бы то же, но Hibernate о нём не знает: загруженные строки
     * остались бы ссылаться на удалённую пачку и уронили бы ближайший flush.
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
