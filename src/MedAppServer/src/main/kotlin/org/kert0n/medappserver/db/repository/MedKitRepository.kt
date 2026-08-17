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
     * Аптечки участника целиком — одним запросом.
     *
     * Возвращаются строки членства: каждая несёт свою аптечку присоединённой, и агрегат
     * собирается без дополнительных обращений. Условие через `EXISTS`, а не по членству
     * напрямую, — нужны **все** участники аптечек вызывающего, иначе состав окажется из одного.
     */
    @Query(
        """
        SELECT m FROM MedKitMembershipData m
        JOIN FETCH m.medKit mk
        WHERE EXISTS (SELECT 1 FROM MedKitMembershipData mine
                      WHERE mine.membershipKey.medKitId = mk.id AND mine.membershipKey.userId = :userId)
        ORDER BY mk.id
    """
    )
    fun findMembershipsOfUserKits(@Param("userId") userId: UUID): List<MedKitMembershipData>
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
