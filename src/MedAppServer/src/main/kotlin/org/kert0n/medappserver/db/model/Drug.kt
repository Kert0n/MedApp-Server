package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Formula
import java.math.BigDecimal
import java.math.RoundingMode
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

    /**
     * Сколько препарата не зарезервировано ни под чей план.
     *
     * Правило «доступно — это остаток минус запланированное» жило единственной строкой в
     * контроллере, хотя тем же вычитанием проверяются инварианты при создании и правке
     * планов. Здесь оно одно на всех.
     */
    val availableQuantity: BigDecimal
        get() = quantity - totalPlannedAmount

    /**
     * Внеплановый расход: забрали лекарство мимо чьего-либо плана.
     *
     * Сумма планов не меняется — она может стать больше остатка, и согласовать её обязан
     * вызывающий. Именно поэтому операция отделена от [consumePlanned]: перепутать их
     * означало бы тихо разойтись с инвариантом.
     */
    fun consumeUnplanned(amount: BigDecimal) {
        quantity = quantity - amount
    }

    /**
     * Приём по плану: остаток и сумма планов уменьшаются на одну и ту же величину.
     *
     * Оба присваивания живут здесь, а не в сервисе, по одной причине: они обязаны идти
     * парой. [totalPlannedAmount] считается формулой при загрузке и внутри транзакции сама
     * не пересчитывается, поэтому её ведут руками — а руками ведённое поле расходится с
     * правдой при первой же правке, если места правки разнесены по разным файлам.
     */
    fun consumePlanned(amount: BigDecimal) {
        quantity = quantity - amount
        totalPlannedAmount = totalPlannedAmount - amount
    }

    /**
     * Сжимает все планы пропорционально, пока их сумма не уложится в остаток.
     *
     * Вызывающий обязан передать препарат с загруженной коллекцией [usings]: по
     * неинициализированной операция тихо не сделает ничего.
     *
     * Инвариант, который нужен коду, — «сумма планов **не больше** остатка», а не точное
     * равенство. Округление вниз даёт его по построению: каждый план не превышает свою точную
     * долю, а доли в сумме дают остаток. Прежняя версия округляла HALF_UP и потому могла
     * получить сумму больше остатка, а следом компенсировала разницу, отдавая её самому
     * большому плану, — то есть третий шаг чинил то, что натворил первый.
     *
     * Коэффициент, наоборот, округляется к ближайшему и с запасом в десять знаков. Вниз
     * округлять и его нельзя: 30 планов при сжатии до двух третей давали 19.999999 вместо 20.
     * Погрешность коэффициента здесь порядка 1e-16, на десять порядков меньше младшего
     * разряда количества, и перекрыть потерю от округления произведений она не может.
     *
     * [totalPlannedAmount] получает настоящую сумму, а не остаток. Поле производное — формула
     * считает `SUM(planned_amount)`, — и приписывать ему `quantity` значит врать себе же: до
     * ближайшей перезагрузки клиент видел бы в `plannedQuantity` завышенное число.
     */
    fun shrinkPlansToStock() {
        if (usings.isEmpty()) {
            totalPlannedAmount = BigDecimal.ZERO
            return
        }
        // Частное обычно бесконечная периодическая дробь, и BigDecimal без явного scale
        // бросил бы ArithmeticException.
        val factor = quantity.divide(totalPlannedAmount, QUANTITY_SCALE + 10, QUANTITY_ROUNDING)
        // setScale здесь явный: сеттер plannedAmount округляет HALF_UP, и без этого
        // произведение приехало бы к нему уже вверх.
        usings.forEach {
            it.plannedAmount = (it.plannedAmount * factor).setScale(QUANTITY_SCALE, RoundingMode.DOWN)
        }
        totalPlannedAmount = usings.fold(BigDecimal.ZERO) { sum, using -> sum + using.plannedAmount }
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

