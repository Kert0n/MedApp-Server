package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Formula
import java.math.BigDecimal
import java.util.*

@Entity
@Table(
    name = "user_drugs",
    indexes = [
        Index(name = "ix_user_drugs_name", columnList = "name"),
        Index(name = "ix_user_drugs_med_kit_id", columnList = "med_kit_id")
    ]
)
class Drug(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @NotNull
    @Size(max = 300)
    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    quantity: BigDecimal,

    @NotNull
    @Size(max = 50)
    @Column(name = "quantity_unit", nullable = false, length = 50)
    var quantityUnit: String,

    @Size(max = 100)
    @Column(name = "form_type", length = 100)
    var formType: String?,

    @Size(max = 200)
    @Column(name = "category", length = 200)
    var category: String?,

    @Size(max = 300)
    @Column(name = "manufacturer", length = 300)
    var manufacturer: String?,

    @Size(max = 100)
    @Column(name = "country", length = 100)
    var country: String?,

    @Column(name = "description", length = Integer.MAX_VALUE)
    var description: String?,

    totalPlannedAmount: BigDecimal = BigDecimal.ZERO,

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "med_kit_id",
        nullable = false,
        foreignKey = ForeignKey(
            name = "user_drugs_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKit

) {

    /** Остаток препарата, нормализованный до масштаба колонки `NUMERIC(19,6)`. */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = 19, scale = QUANTITY_SCALE)
    var quantity: BigDecimal = quantity.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    /**
     * Read-only сумма планов на момент загрузки Drug. Команда обновляет локальное значение,
     * если продолжает использовать его после изменения планов в той же транзакции.
     */
    @Formula("(SELECT COALESCE(SUM(u.planned_amount), 0) FROM usings u WHERE u.drug_id = id)")
    var totalPlannedAmount: BigDecimal = totalPlannedAmount

    /** Незарезервированный остаток. */
    val availableQuantity: BigDecimal
        get() = quantity - totalPlannedAmount

    /** Уменьшает остаток, не меняя сумму планов; reconciliation выполняет оркестратор. */
    fun consumeUnplanned(amount: BigDecimal) {
        quantity = quantity - amount
    }

    /** Атомарно для агрегата уменьшает остаток и локальную сумму планов. */
    fun consumePlanned(amount: BigDecimal) {
        quantity = quantity - amount
        totalPlannedAmount = totalPlannedAmount - amount
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Drug

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
