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
 * Отображение упаковки на таблицу `user_drugs`. Правил здесь нет.
 *
 * Всё, что препарат решает, решает `domain.Drug`; сюда состояние переносится
 * маппером, а SQL из этого делает Hibernate. Поэтому `var` у свойств никого не смущает:
 * этот класс существует ровно затем, чтобы его поля заполняли снаружи.
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

    /**
     * Единица измерения — строка общего справочника, а не свободный текст.
     *
     * `EAGER`: единица нужна всегда, потому что без неё количество не имеет смысла. При
     * загрузке сущности Hibernate забирает её тем же запросом; отдельный SELECT остаётся
     * только у команд, которые берут строку под блокировкой — там fetch join несовместим с
     * `FOR UPDATE`.
     */
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
        // Определение задано явно, чтобы схема, сгенерированная Hibernate в тестах, совпадала
        // с db/schema.sql: иначе каскад проверялся бы только в одной из двух схем.
        foreignKey = ForeignKey(
            name = "user_drugs_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKitData

    // Коллекции броней здесь нет намеренно: упаковка ими не владеет и о них не знает.
    // Исчезновение брони вслед за пачкой держит внешний ключ в `ReservationData`, а не
    // каскад агрегата.
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
