package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Отображение упаковки на `user_drugs`. Правил здесь нет — они в `domain.Drug`.
 *
 * `var` у свойств — требование Hibernate: класс существует затем, чтобы маппер заполнял его
 * поля снаружи.
 */
@Entity
@Table(
    name = "user_drugs",
    indexes = [
        Index(name = "ix_user_drugs_name", columnList = "name"),
        Index(name = "ix_user_drugs_med_kit_id", columnList = "med_kit_id")
    ]
)
class DrugData(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @NotNull
    @Size(max = 300)
    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    @NotNull
    @Column(name = "quantity", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var quantity: BigDecimal,

    /** `EAGER`: без единицы количество не имеет смысла, поэтому она нужна всегда. */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(
        name = "quantity_unit_id",
        nullable = false,
        foreignKey = ForeignKey(name = "user_drugs_quantity_unit_fkey")
    )
    var quantityUnit: QuantityUnitData,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "form_type_id", foreignKey = ForeignKey(name = "user_drugs_form_type_fkey"))
    var formType: FormTypeData?,

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
        // Определение задано явно, чтобы схема Hibernate в тестах совпадала с db/schema.sql:
        // иначе каскад проверялся бы только в одной из двух схем.
        foreignKey = ForeignKey(
            name = "user_drugs_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKitData,

    // Коллекции броней здесь нет: упаковка ими не владеет. Их исчезновение вслед за пачкой
    // держит внешний ключ в `ReservationData`.

    /**
     * Версией распоряжается Hibernate, присваивать её нельзя.
     *
     * Дочерних строк у упаковки нет, поэтому её двигает обычный dirty checking: любая команда
     * над пачкой меняет саму эту строку.
     */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DrugData

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
