package org.kert0n.medappserver.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Разрядность колонок остатка и плана: `NUMERIC(19,6)`. */
const val QUANTITY_PRECISION: Int = 19

/** Масштаб тех же колонок. Шесть знаков покрывают деление таблетки и капли раствора. */
const val QUANTITY_SCALE: Int = 6

/**
 * Округление вниз, а не HALF_UP: любое количество здесь — обещание, что столько препарата
 * действительно есть. Округление вверх создало бы остаток или план из воздуха и позволило бы
 * сумме планов превысить остаток на разряд.
 */
val QUANTITY_ROUNDING: RoundingMode = RoundingMode.DOWN

/**
 * Количество препарата: число вместе с единицей измерения.
 *
 * Раньше это была пара «`BigDecimal` и строка», причём строка лежала только у препарата, а у
 * плана лечения единицы не было вовсе — она подразумевалась. Здесь они неразделимы, поэтому
 * сложить остаток в таблетках с планом в миллилитрах нельзя даже случайно.
 *
 * Нормализация живёт тут же: величина не бывает ненормализованной, и ни границе API, ни
 * сеттеру отображения больше не приходится за этим следить.
 */
class Quantity(amount: BigDecimal, val unit: QuantityUnit) : Comparable<Quantity> {

    val amount: BigDecimal = amount.setScale(QUANTITY_SCALE, QUANTITY_ROUNDING)

    init {
        if (this.amount.signum() < 0) throw NegativeQuantity()
    }

    val isZero: Boolean get() = amount.signum() == 0

    val isPositive: Boolean get() = amount.signum() > 0

    /** Ноль в той же единице: с него начинается сложение величин. */
    fun zero(): Quantity = Quantity(BigDecimal.ZERO, unit)

    operator fun plus(other: Quantity): Quantity = Quantity(amount + sameUnit(other), unit)

    operator fun minus(other: Quantity): Quantity = Quantity(amount - sameUnit(other), unit)

    override fun compareTo(other: Quantity): Int = amount.compareTo(sameUnit(other))

    /**
     * Доля этой величины, соответствующая отношению `numerator / denominator`.
     *
     * Умножение идёт до деления: отдельный коэффициент пришлось бы округлить, и деление 60 на
     * 90 превратило бы план 30 в 19.999999 вместо 20. При таком порядке точное частное
     * получается там, где оно вообще существует, а округление вниз оставляет инвариант в
     * силе — сумма долей не превышает целого.
     */
    fun timesRatio(numerator: Quantity, denominator: Quantity): Quantity = Quantity(
        amount.multiply(sameUnit(numerator)).divide(sameUnit(denominator), QUANTITY_SCALE, QUANTITY_ROUNDING),
        unit
    )

    private fun sameUnit(other: Quantity): BigDecimal {
        if (unit != other.unit) throw QuantityUnitMismatch()
        return other.amount
    }

    /** Величина — значение: равны те, у кого совпадают число и единица. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Quantity && unit == other.unit && amount.compareTo(other.amount) == 0)

    override fun hashCode(): Int = 31 * unit.hashCode() + amount.stripTrailingZeros().hashCode()

    override fun toString(): String = "$amount ${unit.name}"
}

/**
 * Единица измерения из общего справочника.
 *
 * Идентификатор, а не просто имя: та же таблица стоит за каталогом препаратов, поэтому «шт»
 * у заведённого руками препарата и «шт» у карточки справочника — одна и та же единица, а не
 * две одинаково написанные строки.
 */
data class QuantityUnit(
    val id: UUID,
    val name: String
)
