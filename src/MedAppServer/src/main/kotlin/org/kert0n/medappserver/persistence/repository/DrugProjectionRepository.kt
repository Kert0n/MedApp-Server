package org.kert0n.medappserver.persistence.repository

import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DrugProjectionRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun findAccessible(userId: UUID, drugId: UUID): DrugResult? = jdbc.query(
        """
            SELECT drug.*,
                   COALESCE(SUM(plan.planned_amount), 0) AS planned_quantity
            FROM user_drugs drug
            JOIN user_med_kits membership ON membership.med_kit_id = drug.med_kit_id
            LEFT JOIN usings plan ON plan.drug_id = drug.id
            WHERE drug.id = :drugId AND membership.user_id = :userId
            GROUP BY drug.id
        """.trimIndent(),
        mapOf("userId" to userId, "drugId" to drugId)
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
    }.firstOrNull()

    fun plansForUser(userId: UUID): List<TreatmentPlanResult> = jdbc.query(
        """
            SELECT user_id, drug_id, planned_amount
            FROM usings
            WHERE user_id = :userId
            ORDER BY drug_id
        """.trimIndent(),
        mapOf("userId" to userId)
    ) { row, _ ->
        TreatmentPlanResult(
            userId = row.getObject("user_id", UUID::class.java),
            drugId = row.getObject("drug_id", UUID::class.java),
            plannedAmount = row.getBigDecimal("planned_amount")
        )
    }

    fun planForUser(userId: UUID, drugId: UUID): TreatmentPlanResult? = jdbc.query(
        """
            SELECT user_id, drug_id, planned_amount
            FROM usings
            WHERE user_id = :userId AND drug_id = :drugId
        """.trimIndent(),
        mapOf("userId" to userId, "drugId" to drugId)
    ) { row, _ ->
        TreatmentPlanResult(
            userId = row.getObject("user_id", UUID::class.java),
            drugId = row.getObject("drug_id", UUID::class.java),
            plannedAmount = row.getBigDecimal("planned_amount")
        )
    }.firstOrNull()
}
