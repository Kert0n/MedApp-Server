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

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "med_kit_id",
        nullable = false,
        // Определение задано явно, чтобы схема, сгенерированная Hibernate в тестах, совпадала
        // с db/schema.sql: иначе каскад проверялся бы только в одной из двух схем.
        foreignKey = ForeignKey(
            name = "user_drugs_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKit,

    @OneToMany(mappedBy = "drug", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    var usings: MutableSet<Using> = mutableSetOf()

) {

    /** Остаток препарата, нормализованный до масштаба колонки `NUMERIC(19,6)`. */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var quantity: BigDecimal = quantity.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    /**
     * Сумма планов, вычисленная базой при загрузке: единственное определение этой величины.
     *
     * Собственного столбца нет — подзапрос подставляется в тот же SELECT, которым читается
     * препарат, поэтому список приходит вместе с суммами одним запросом. Присваивание
     * меняет только копию в памяти текущей транзакции и не сохраняется; `var` стоит потому,
     * что поле заполняет Hibernate, а команды правят его вручную, компенсируя устаревание
     * внутри транзакции. И присваивание, и сама формула уходят вместе с переездом планов
     * внутрь агрегата Drug.
     */
    @Formula("(SELECT COALESCE(SUM(u.planned_amount), 0) FROM usings u WHERE u.drug_id = id)")
    var totalPlannedAmount: BigDecimal = BigDecimal.ZERO

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
