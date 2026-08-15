package org.kert0n.medappserver.persistence.repository

import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.MedKitContentView
import org.kert0n.medappserver.application.model.MedKitSummaryView
import org.kert0n.medappserver.application.model.UserSnapshotView
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class MedKitProjectionRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun listForUser(userId: UUID): List<MedKitSummaryView> = jdbc.query(
        """
            SELECT med_kit.id,
                   COUNT(DISTINCT membership.user_id) AS user_count,
                   COUNT(DISTINCT drug.id) AS drug_count
            FROM med_kits med_kit
            JOIN user_med_kits access ON access.med_kit_id = med_kit.id AND access.user_id = :userId
            LEFT JOIN user_med_kits membership ON membership.med_kit_id = med_kit.id
            LEFT JOIN user_drugs drug ON drug.med_kit_id = med_kit.id
            GROUP BY med_kit.id
            ORDER BY med_kit.id
        """.trimIndent(),
        mapOf("userId" to userId)
    ) { row, _ ->
        MedKitSummaryView(
            id = row.getObject("id", UUID::class.java),
            userCount = row.getLong("user_count"),
            drugCount = row.getLong("drug_count")
        )
    }

    fun accessibleMedKitId(userId: UUID, medKitId: UUID): UUID? = jdbc.query(
        """
            SELECT med_kit_id
            FROM user_med_kits
            WHERE user_id = :userId AND med_kit_id = :medKitId
        """.trimIndent(),
        mapOf("userId" to userId, "medKitId" to medKitId)
    ) { row, _ -> row.getObject("med_kit_id", UUID::class.java) }.firstOrNull()

    fun content(userId: UUID, medKitId: UUID): MedKitContentView? {
        val id = accessibleMedKitId(userId, medKitId) ?: return null
        return MedKitContentView(id, drugs(listOf(id)))
    }

    fun snapshot(userId: UUID): UserSnapshotView {
        val medKitIds = jdbc.queryForList(
            "SELECT med_kit_id FROM user_med_kits WHERE user_id = :userId ORDER BY med_kit_id",
            mapOf("userId" to userId),
            UUID::class.java
        )
        if (medKitIds.isEmpty()) return UserSnapshotView(userId, emptyList())
        val drugs = drugs(medKitIds).groupBy(DrugResult::medKitId)
        return UserSnapshotView(
            id = userId,
            medKits = medKitIds.map { MedKitContentView(it, drugs[it].orEmpty()) }
        )
    }

    private fun drugs(medKitIds: Collection<UUID>): List<DrugResult> = jdbc.query(
        """
            SELECT drug.*,
                   COALESCE(SUM(plan.planned_amount), 0) AS planned_quantity
            FROM user_drugs drug
            LEFT JOIN usings plan ON plan.drug_id = drug.id
            WHERE drug.med_kit_id IN (:medKitIds)
            GROUP BY drug.id
            ORDER BY drug.med_kit_id, drug.id
        """.trimIndent(),
        mapOf("medKitIds" to medKitIds)
    ) { row, _ ->
        val quantity = row.getBigDecimal("quantity")
        val planned = row.getBigDecimal("planned_quantity")
        DrugResult(
            id = row.getObject("id", UUID::class.java),
            medKitId = row.getObject("med_kit_id", UUID::class.java),
            name = row.getString("name"),
            quantity = quantity,
            plannedQuantity = planned,
            availableQuantity = quantity - planned,
            quantityUnit = row.getString("quantity_unit"),
            formType = row.getString("form_type"),
            category = row.getString("category"),
            manufacturer = row.getString("manufacturer"),
            country = row.getString("country"),
            description = row.getString("description")
        )
    }
}
