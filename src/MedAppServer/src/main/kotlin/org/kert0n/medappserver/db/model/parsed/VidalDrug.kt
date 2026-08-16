package org.kert0n.medappserver.db.model.parsed

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.GeneratedColumn
import java.util.*

/**
 * Каталог препаратов, по которому ищет приложение.
 *
 * Наполняется дампом скраппера. Выгрузка приходит со своей таблицей `drugs` и своими
 * колонками (`drug_id`, `form`, `dosage`, `url`), которых здесь нет; приведение к этой
 * форме делает `db/rewrite-catalogue-dump.py` над самим файлом дампа, а не база при
 * инициализации — иначе справочник записывался бы дважды.
 *
 * Не путать с `user_drugs` — это препараты в аптечках пользователей, свои данные, а не
 * справочник.
 */
@Entity
@Table(
    // GIN/trigram indexes live in schema.sql because JPA cannot express their opclasses.
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
        )]
)
class VidalDrug(
    @Id
    @Column(name = "id", nullable = false) var id: UUID = UUID.randomUUID(),

    @Size(max = 300)
    @NotNull
    @Column(name = "name", nullable = false, length = 300) var name: String,

    /** Optional international name written in Latin characters. */
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
    @NotNull var otc: Boolean,

    /** Database-generated full-text document; application code never writes it. */
    @Column(name = "search_tsv", insertable = false, updatable = false, columnDefinition = "tsvector")
    @GeneratedColumn(
        "to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' || " +
            "coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))"
    )
    var searchTsv: String? = null


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
