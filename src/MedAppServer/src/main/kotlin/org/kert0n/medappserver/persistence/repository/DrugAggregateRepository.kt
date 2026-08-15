package org.kert0n.medappserver.persistence.repository

import org.kert0n.medappserver.domain.drug.CreateDrug
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.drug.TreatmentPlan
import org.kert0n.medappserver.domain.drug.TreatmentPlanBook
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

data class LoadedDrugAggregate(
    val drug: Drug,
    val plans: TreatmentPlanBook
)

@Repository
class DrugAggregateRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun findAccessible(userId: UUID, drugId: UUID): Drug? = findDrug(userId, drugId, lock = false)

    fun lockAccessible(userId: UUID, drugId: UUID): Drug? = findDrug(userId, drugId, lock = true)

    fun lockAggregate(userId: UUID, drugId: UUID): LoadedDrugAggregate? {
        val drug = lockAccessible(userId, drugId) ?: return null
        return LoadedDrugAggregate(drug, loadPlans(drugId))
    }

    fun loadPlans(drugId: UUID): TreatmentPlanBook {
        val plans = jdbc.query(
            """
                SELECT user_id, planned_amount
                FROM usings
                WHERE drug_id = :drugId
                ORDER BY user_id
            """.trimIndent(),
            mapOf("drugId" to drugId)
        ) { row, _ ->
            TreatmentPlan.create(
                userId = row.getObject("user_id", UUID::class.java),
                drugId = drugId,
                plannedAmount = row.getBigDecimal("planned_amount")
            )
        }
        return TreatmentPlanBook(drugId, plans)
    }

    fun lockAllByMedKitIds(medKitIds: Collection<UUID>): List<UUID> {
        if (medKitIds.isEmpty()) return emptyList()
        return jdbc.queryForList(
            """
                SELECT id
                FROM user_drugs
                WHERE med_kit_id IN (:medKitIds)
                ORDER BY id
                FOR UPDATE
            """.trimIndent(),
            mapOf("medKitIds" to medKitIds),
            UUID::class.java
        )
    }

    fun insert(drug: Drug) {
        jdbc.update(
            """
                INSERT INTO user_drugs (
                    id, med_kit_id, name, quantity, quantity_unit, form_type,
                    category, manufacturer, country, description
                ) VALUES (
                    :id, :medKitId, :name, :quantity, :quantityUnit, :formType,
                    :category, :manufacturer, :country, :description
                )
            """.trimIndent(),
            drug.parameters()
        )
    }

    fun update(drug: Drug) {
        check(
            jdbc.update(
                """
                    UPDATE user_drugs
                    SET med_kit_id = :medKitId,
                        name = :name,
                        quantity = :quantity,
                        quantity_unit = :quantityUnit,
                        form_type = :formType,
                        category = :category,
                        manufacturer = :manufacturer,
                        country = :country,
                        description = :description
                    WHERE id = :id
                """.trimIndent(),
                drug.parameters()
            ) == 1
        ) { "Drug ${drug.id} disappeared while locked" }
    }

    fun insert(plan: TreatmentPlan) {
        jdbc.update(
            """
                INSERT INTO usings (user_id, drug_id, planned_amount)
                VALUES (:userId, :drugId, :plannedAmount)
            """.trimIndent(),
            mapOf(
                "userId" to plan.userId,
                "drugId" to plan.drugId,
                "plannedAmount" to plan.plannedAmount
            )
        )
    }

    fun update(plan: TreatmentPlan) {
        check(
            jdbc.update(
                """
                    UPDATE usings
                    SET planned_amount = :plannedAmount
                    WHERE user_id = :userId AND drug_id = :drugId
                """.trimIndent(),
                mapOf(
                    "userId" to plan.userId,
                    "drugId" to plan.drugId,
                    "plannedAmount" to plan.plannedAmount
                )
            ) == 1
        ) { "Treatment plan disappeared while its drug was locked" }
    }

    fun update(plans: Collection<TreatmentPlan>) {
        plans.forEach(::update)
    }

    fun deletePlan(userId: UUID, drugId: UUID): Int = jdbc.update(
        "DELETE FROM usings WHERE user_id = :userId AND drug_id = :drugId",
        mapOf("userId" to userId, "drugId" to drugId)
    )

    fun deletePlansWithoutTargetAccess(drugId: UUID, targetMedKitId: UUID): Int = jdbc.update(
        """
            DELETE FROM usings plan
            WHERE plan.drug_id = :drugId
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_med_kits membership
                  WHERE membership.med_kit_id = :targetMedKitId
                    AND membership.user_id = plan.user_id
              )
        """.trimIndent(),
        mapOf("drugId" to drugId, "targetMedKitId" to targetMedKitId)
    )

    fun move(drugId: UUID, targetMedKitId: UUID): Int = jdbc.update(
        "UPDATE user_drugs SET med_kit_id = :targetMedKitId WHERE id = :drugId",
        mapOf("drugId" to drugId, "targetMedKitId" to targetMedKitId)
    )

    fun deletePlansWithoutTargetAccessByMedKit(sourceMedKitId: UUID, targetMedKitId: UUID): Int = jdbc.update(
        """
            DELETE FROM usings plan
            USING user_drugs drug
            WHERE plan.drug_id = drug.id
              AND drug.med_kit_id = :sourceMedKitId
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_med_kits target_membership
                  WHERE target_membership.med_kit_id = :targetMedKitId
                    AND target_membership.user_id = plan.user_id
              )
        """.trimIndent(),
        mapOf("sourceMedKitId" to sourceMedKitId, "targetMedKitId" to targetMedKitId)
    )

    fun moveAll(sourceMedKitId: UUID, targetMedKitId: UUID): Int = jdbc.update(
        """
            UPDATE user_drugs
            SET med_kit_id = :targetMedKitId
            WHERE med_kit_id = :sourceMedKitId
        """.trimIndent(),
        mapOf("sourceMedKitId" to sourceMedKitId, "targetMedKitId" to targetMedKitId)
    )

    fun delete(drugId: UUID): Int = jdbc.update(
        "DELETE FROM user_drugs WHERE id = :drugId",
        mapOf("drugId" to drugId)
    )

    fun targetMemberIds(medKitId: UUID): Set<UUID> = jdbc.queryForList(
        "SELECT user_id FROM user_med_kits WHERE med_kit_id = :medKitId ORDER BY user_id",
        mapOf("medKitId" to medKitId),
        UUID::class.java
    ).toSet()

    private fun findDrug(userId: UUID, drugId: UUID, lock: Boolean): Drug? {
        val lockClause = if (lock) "FOR UPDATE OF drug" else ""
        return try {
            jdbc.queryForObject(
                """
                    SELECT drug.*
                    FROM user_drugs drug
                    JOIN user_med_kits membership ON membership.med_kit_id = drug.med_kit_id
                    WHERE drug.id = :drugId AND membership.user_id = :userId
                    $lockClause
                """.trimIndent(),
                mapOf("drugId" to drugId, "userId" to userId),
                ::mapDrug
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
    }

    private fun mapDrug(row: ResultSet, ignoredRowNumber: Int): Drug = Drug.create(
        CreateDrug(
            id = row.getObject("id", UUID::class.java),
            medKitId = row.getObject("med_kit_id", UUID::class.java),
            name = row.getString("name"),
            quantity = row.getBigDecimal("quantity"),
            quantityUnit = row.getString("quantity_unit"),
            formType = row.getString("form_type"),
            category = row.getString("category"),
            manufacturer = row.getString("manufacturer"),
            country = row.getString("country"),
            description = row.getString("description")
        )
    )

    private fun Drug.parameters(): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("id", id)
        .addValue("medKitId", medKitId)
        .addValue("name", name)
        .addValue("quantity", quantity)
        .addValue("quantityUnit", quantityUnit)
        .addValue("formType", formType)
        .addValue("category", category)
        .addValue("manufacturer", manufacturer)
        .addValue("country", country)
        .addValue("description", description)
}
