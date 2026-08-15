package org.kert0n.medappserver.persistence.repository

import org.kert0n.medappserver.domain.medkit.MedKit
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class MedKitAggregateRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun userExists(userId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = :userId)",
        mapOf("userId" to userId),
        Boolean::class.java
    ) == true

    fun insert(medKit: MedKit, ownerId: UUID) {
        jdbc.update("INSERT INTO med_kits (id) VALUES (:id)", mapOf("id" to medKit.id))
        insertMembership(medKit.id, ownerId)
    }

    fun lockAccessible(userId: UUID, medKitIds: Collection<UUID>): List<UUID> {
        if (medKitIds.isEmpty()) return emptyList()
        return jdbc.queryForList(
            """
                SELECT med_kit.id
                FROM med_kits med_kit
                JOIN user_med_kits membership ON membership.med_kit_id = med_kit.id
                WHERE membership.user_id = :userId AND med_kit.id IN (:medKitIds)
                ORDER BY med_kit.id
                FOR UPDATE OF med_kit
            """.trimIndent(),
            mapOf("userId" to userId, "medKitIds" to medKitIds),
            UUID::class.java
        )
    }

    fun load(medKitId: UUID): MedKit? = try {
        val id = jdbc.queryForObject(
            "SELECT id FROM med_kits WHERE id = :medKitId",
            mapOf("medKitId" to medKitId),
            UUID::class.java
        ) ?: return null
        MedKit.restore(id, memberIds(id))
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun lock(medKitId: UUID): UUID? = try {
        jdbc.queryForObject(
            "SELECT id FROM med_kits WHERE id = :medKitId FOR UPDATE",
            mapOf("medKitId" to medKitId),
            UUID::class.java
        )
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun memberIds(medKitId: UUID): Set<UUID> = jdbc.queryForList(
        "SELECT user_id FROM user_med_kits WHERE med_kit_id = :medKitId ORDER BY user_id",
        mapOf("medKitId" to medKitId),
        UUID::class.java
    ).toSet()

    fun insertMembership(medKitId: UUID, userId: UUID): Int = jdbc.update(
        """
            INSERT INTO user_med_kits (med_kit_id, user_id)
            VALUES (:medKitId, :userId)
            ON CONFLICT DO NOTHING
        """.trimIndent(),
        mapOf("medKitId" to medKitId, "userId" to userId)
    )

    fun deleteMembership(medKitId: UUID, userId: UUID): Int = jdbc.update(
        "DELETE FROM user_med_kits WHERE med_kit_id = :medKitId AND user_id = :userId",
        mapOf("medKitId" to medKitId, "userId" to userId)
    )

    fun deletePlansForMember(medKitId: UUID, userId: UUID): Int = jdbc.update(
        """
            DELETE FROM usings plan
            USING user_drugs drug
            WHERE plan.drug_id = drug.id
              AND drug.med_kit_id = :medKitId
              AND plan.user_id = :userId
        """.trimIndent(),
        mapOf("medKitId" to medKitId, "userId" to userId)
    )

    fun delete(medKitId: UUID): Int = jdbc.update(
        "DELETE FROM med_kits WHERE id = :medKitId",
        mapOf("medKitId" to medKitId)
    )
}
