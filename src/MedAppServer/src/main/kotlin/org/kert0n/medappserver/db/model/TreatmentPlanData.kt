package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE
import java.io.Serializable
import java.math.BigDecimal
import java.util.*


@Entity
@Table(
    name = "usings",
    indexes = [
        Index(name = "ix_usings_user_id", columnList = "user_id"),
        Index(name = "ix_usings_drug_id", columnList = "drug_id")
    ]
)
class TreatmentPlanData(

    @EmbeddedId
    var planKey: TreatmentPlanKey = TreatmentPlanKey(),

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("userId")
    // Имя задано явно, чтобы схема Hibernate и db/schema.sql совпадали и по именам ключей.
    // Каскада здесь намеренно нет: план не удаляется вслед за пользователем.
    @JoinColumn(name = "user_id", foreignKey = ForeignKey(name = "usings_user_fkey"))
    var userData: UserData,

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("drugId")
    @JoinColumn(
        name = "drug_id",
        // Как и у Drug.medKit: явное определение держит схему Hibernate и db/schema.sql
        // в одном каскадном контракте.
        foreignKey = ForeignKey(
            name = "usings_drug_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE"
        )
    )
    var drugData: DrugData,

    plannedAmount: BigDecimal
) {

    /** Запланированное количество; масштаб обеспечивает `domain.Quantity`. */
    @NotNull
    @Column(name = "planned_amount", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var plannedAmount: BigDecimal = plannedAmount

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TreatmentPlanData

        return planKey == other.planKey
    }

    override fun hashCode(): Int {
        return planKey.hashCode()
    }
}

@Suppress("JpaDataSourceORMInspection")
@Embeddable
class TreatmentPlanKey(
    @Column(name = "user_id")
    var userId: UUID = UUID(0, 0),
    @Column(name = "drug_id")
    var drugId: UUID = UUID(0, 0)
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TreatmentPlanKey

        return userId == other.userId && drugId == other.drugId
    }

    override fun hashCode(): Int {
        return Objects.hash(userId, drugId)
    }
}

