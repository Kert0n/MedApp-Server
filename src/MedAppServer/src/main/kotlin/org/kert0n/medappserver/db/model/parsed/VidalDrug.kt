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
    // Индексы названы по своей таблице. Раньше они назывались ix_drugs_*/idx_drugs_* и
    // сталкивались с одноимёнными индексами таблицы drugs, которую приносил дамп, — имена
    // индексов в Postgres уникальны на схему, поэтому init базы падал.
    //
    // Список неполный намеренно: индексы поиска — GIN по триграммам и по выражению
    // to_tsvector — объявлены только в db/schema.sql, потому что JPA не выражает ни тип
    // индекса, ни opclass. Btree по active_substance и manufacturer убраны оттуда же: для
    // `LIKE '%…%'` и similarity() они бесполезны, а вставку замедляют.
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
    @NotNull var otc: Boolean,

    /**
     * Склейка искомых полей для полнотекстового поиска; считает база.
     *
     * Отображена только ради того, чтобы схема и модель не расходились: значение
     * генерируемое, поэтому `insertable`/`updatable` сняты — приложение его не пишет и не
     * читает, обращается к нему только нативный запрос поиска по имени колонки.
     *
     * Без этого объявления колонки не было бы в схеме, которую Hibernate создаёт для тестов,
     * и поиск падал бы на «column search_tsv does not exist» — притом что в проде, где схема
     * берётся из `db/schema.sql`, всё работало бы.
     *
     * Колонка, а не выражение в индексе: по индексу от выражения Hibernate при старте не
     * может сопоставить колонку и пишет HHH000475.
     *
     * Выражение объявлено через [GeneratedColumn], а не внутри `columnDefinition`. Разница
     * принципиальная: `columnDefinition` Hibernate подставляет в DDL целиком и **им же**
     * сравнивает при `validate`, поэтому вариант со встроенным `GENERATED ALWAYS AS` создавал
     * схему в тестах, но валил прод — база сообщает тип `tsvector`, а ожидалась вся строка
     * определения. Проверено на стенде. `@GeneratedColumn` разводит эти две роли: в DDL
     * выражение попадает, в сравнение типов — нет.
     */
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