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
    @JoinColumn(name = "med_kit_id", nullable = false)
    var medKit: MedKit,

    @OneToMany(mappedBy = "drug", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    var usings: MutableSet<Using> = mutableSetOf()

) {

    /**
     * Остаток препарата. Значение всегда нормализовано до [QUANTITY_SCALE].
     *
     * Приведение живёт в сеттере, а не в вызывающем коде: иначе каждая арифметическая строка в
     * сервисах обрастает `.toQuantityScale()`, и достаточно один раз забыть — как scale начинает
     * накапливаться (умножение складывает scale операндов), значения перестают совпадать с
     * прочитанными из базы, и сравнения через `equals` начинают врать.
     *
     * Hibernate работает с полями напрямую (доступ FIELD, потому что `@Id` стоит на поле),
     * поэтому при загрузке из БД сеттер не вызывается — и это правильно: в колонке
     * `numeric(19,6)` значение уже нужного вида.
     */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = 19, scale = QUANTITY_SCALE)
    var quantity: BigDecimal = quantity.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    /**
     * Сумма всех планов по препарату — производное значение, его считает формула при загрузке.
     *
     * В отличие от [quantity], нормализовать здесь нечего: колонки под это поле нет, в базу
     * оно не пишется, а присваивания в коде живут только внутри транзакции как замена
     * перезагрузке препарата. Сравнивается оно через `compareTo`, которому scale безразличен.
     * Сеттер с приведением scale тут был бы вдвойне бесполезен: при загрузке Hibernate пишет
     * прямо в поле и сеттер обходит, то есть значение из формулы через него всё равно не идёт.
     *
     * Из этого следует, что формула возвращает разный scale — без планов
     * `COALESCE(SUM(...), 0)` даёт целочисленный ноль. Поэтому сравнивать это поле можно
     * только по значению, никогда через `equals`.
     */
    @Formula("(SELECT COALESCE(SUM(u.planned_amount), 0) FROM usings u WHERE u.drug_id = id)")
    var totalPlannedAmount: BigDecimal = totalPlannedAmount

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

