package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import org.kert0n.medappserver.domain.quantity.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.quantity.QUANTITY_SCALE
import org.kert0n.medappserver.domain.quantity.toQuantityScale
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
class TreatmentPlan(

    @EmbeddedId
    var key: TreatmentPlanKey = TreatmentPlanKey(),

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(
        name = "user_id",
        foreignKey = ForeignKey(name = "usings_user_fkey")
    )
    var user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("drugId")
    @JoinColumn(
        name = "drug_id",
        foreignKey = ForeignKey(
            name = "usings_drug_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE"
        )
    )
    var drug: Drug,

    plannedAmount: BigDecimal
) {

    @NotNull
    @Column(name = "planned_amount", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var plannedAmount: BigDecimal = plannedAmount.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TreatmentPlan

        return key == other.key
    }

    override fun hashCode(): Int {
        return key.hashCode()
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
