package org.kert0n.medappserver.persistence.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class MedKitAccessRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
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

    fun memberIds(medKitId: UUID): Set<UUID> = jdbc.queryForList(
        "SELECT user_id FROM user_med_kits WHERE med_kit_id = :medKitId ORDER BY user_id",
        mapOf("medKitId" to medKitId),
        UUID::class.java
    ).toSet()
}
