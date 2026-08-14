package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
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
class Using(

    @EmbeddedId
    var usingKey: UsingKey = UsingKey(),

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    var user: User,

    @ManyToOne(fetch = FetchType.EAGER)
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

    /**
     * Запланированное количество. Нормализуется до [QUANTITY_SCALE] в сеттере — по тем же
     * причинам, что и [Drug.quantity]: приведение в одном месте вместо повтора на каждой
     * арифметической строке в сервисах.
     */
    @NotNull
    @Column(name = "planned_amount", nullable = false, precision = 19, scale = QUANTITY_SCALE)
    var plannedAmount: BigDecimal = plannedAmount.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    /**
     * Уменьшает план на принятое количество, не уходя ниже нуля.
     *
     * Отсечение по нулю — часть инварианта плана, а не деталь вызывающего: отрицательный
     * план не значит ничего, и допускать его хотя бы на время транзакции незачем.
     */
    fun reduceBy(amount: BigDecimal) {
        plannedAmount = maxOf(BigDecimal.ZERO, plannedAmount - amount)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Using

        return usingKey == other.usingKey
    }

    override fun hashCode(): Int {
        return usingKey.hashCode()
    }
}

@Suppress("JpaDataSourceORMInspection")
@Embeddable
class UsingKey(
    @Column(name = "user_id")
    var userId: UUID = UUID(0, 0),
    @Column(name = "drug_id")
    var drugId: UUID = UUID(0, 0)
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UsingKey

        return userId == other.userId && drugId == other.drugId
    }

    override fun hashCode(): Int {
        return Objects.hash(userId, drugId)
    }
}
