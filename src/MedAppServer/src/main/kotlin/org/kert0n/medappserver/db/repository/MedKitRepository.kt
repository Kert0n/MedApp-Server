package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.MedKitMembershipData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MedKitRepository : JpaRepository<MedKitData, UUID> {

    /**
     * Аптечки участника — идентификатор и версия, без состава.
     *
     * Снимку пользователя больше ничего и не нужно: препараты он берёт отдельным запросом, а
     * участники аптечек в ответе не участвуют — поднимать их значило бы платить за то, чего
     * никто не прочитает. Версия при этом обязана быть: без неё выход из аптечки требовал бы
     * второго запроса за тем, что снимок уже держал в руках.
     */
    @Query(
        """
        SELECT new org.kert0n.medappserver.domain.MedKitRef(mk.id, mk.version)
        FROM MedKitData mk
        JOIN MedKitMembershipData m ON m.membershipKey.medKitId = mk.id
        WHERE m.membershipKey.userId = :userId
        ORDER BY mk.id
    """
    )
    fun findRefsOfUser(@Param("userId") userId: UUID): List<org.kert0n.medappserver.domain.MedKitRef>

    /**
     * Счётчики аптечек участника одним запросом.
     *
     * Считает база: поднимать участников и препараты, чтобы узнать, сколько их, значит
     * грузить два чужих агрегата ради двух чисел.
     */
    @Query(
        """
        SELECT new org.kert0n.medappserver.domain.MedKitOverview(
            mk.id,
            mk.version,
            (SELECT COUNT(m2) FROM MedKitMembershipData m2 WHERE m2.membershipKey.medKitId = mk.id),
            (SELECT COUNT(d) FROM DrugData d WHERE d.medKit = mk))
        FROM MedKitData mk
        JOIN MedKitMembershipData m ON m.membershipKey.medKitId = mk.id
        WHERE m.membershipKey.userId = :userId
        ORDER BY mk.id
    """
    )
    fun findOverviewsOfUser(@Param("userId") userId: UUID): List<org.kert0n.medappserver.domain.MedKitOverview>
}

interface MedKitMembershipRepository : JpaRepository<MedKitMembershipData, org.kert0n.medappserver.db.model.MedKitMembershipKey> {

    @Query("SELECT m.membershipKey.userId FROM MedKitMembershipData m WHERE m.membershipKey.medKitId = :medKitId")
    fun findMemberIds(@Param("medKitId") medKitId: UUID): Set<UUID>

    /** Строки членства аптечки: нужны, когда её удаляют. */
    @Query("SELECT m FROM MedKitMembershipData m WHERE m.membershipKey.medKitId = :medKitId")
    fun findAllOfMedKit(@Param("medKitId") medKitId: UUID): List<MedKitMembershipData>

    @Modifying
    @Query("DELETE FROM MedKitMembershipData m WHERE m.membershipKey.medKitId = :medKitId AND m.membershipKey.userId IN :userIds")
    fun deleteMembers(@Param("medKitId") medKitId: UUID, @Param("userIds") userIds: Collection<UUID>)
}
