package org.kert0n.medappserver.db.model.parsed

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.*

/**
 * Каталог препаратов, по которому ищет приложение.
 *
 * Не путать с таблицей `drugs` из дампа справочника: та принадлежит внешнему источнику и
 * несёт его колонки (`drug_id`, `name_lat`, `form`, `dosage`, `url`). `parsed_drugs`
 * наполняется переносом из неё, см. `db/fill-parsed-drugs.sql`.
 */
@Entity
@Table(
    // Индексы названы по своей таблице. Раньше они назывались ix_drugs_*/idx_drugs_* и
    // сталкивались с одноимёнными индексами таблицы drugs из дампа — имена индексов в
    // Postgres уникальны на схему, поэтому init базы падал.
    name = "parsed_drugs", indexes = [
        Index(
            name = "ix_parsed_drugs_name",
            columnList = "name"
        ),
        Index(
            name = "ix_parsed_drugs_form_type_id",
            columnList = "form_type_id"
        ),
        Index(
            name = "ix_parsed_drugs_quantity_unit_id",
            columnList = "quantity_unit_id"
        ),
        Index(
            name = "ix_parsed_drugs_active_substance",
            columnList = "active_substance"
        ),
        Index(
            name = "ix_parsed_drugs_manufacturer",
            columnList = "manufacturer"
        )]
)
class VidalDrug(
    @Id
    @Column(name = "id", nullable = false) var id: UUID = UUID.randomUUID(),

    @Size(max = 300)
    @NotNull
    @Column(name = "name", nullable = false, length = 300) var name: String,

    /**
     * Международное название латиницей.
     *
     * Nullable: в справочнике заполнено у 17646 записей из 18087. До этого колонка при
     * переносе из дампа отбрасывалась, из-за чего поиск по латинскому названию не находил
     * ничего вообще — снаружи это выглядело как «поиск не работает с языками».
     */
    @Size(max = 300)
    @Column(name = "name_lat", length = 300) var nameLat: String? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "form_type_id") var formType: FormType? = null,

    @Column(name = "quantity") var quantity: Int? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "quantity_unit_id") var quantityUnit: QuantityUnit? = null,

    @Size(max = 300)
    @Column(name = "active_substance", length = 300) var activeSubstance: String? = null,

    @Size(max = 300)
    @Column(name = "category", length = 300) var category: String? = null,

    @Size(max = 300)
    @NotNull
    @Column(name = "manufacturer", nullable = false, length = 300) var manufacturer: String,

    @Size(max = 100)
    @Column(name = "country", length = 100) var country: String? = null,

    @Column(name = "description", length = Integer.MAX_VALUE) var description: String? = null,

    @Column(name = "otc", nullable = false)
    @NotNull var otc: Boolean


) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VidalDrug

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}