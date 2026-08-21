package org.kert0n.medappserver.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** Разрядность колонок количества: `NUMERIC(19,6)`. */
const val QUANTITY_PRECISION: Int = 19

/** Масштаб тех же колонок. Шесть знаков покрывают деление таблетки и капли раствора. */
const val QUANTITY_SCALE: Int = 6

/** Вниз, а не HALF_UP: округление вверх создало бы количество, которого нет. */
val QUANTITY_ROUNDING: RoundingMode = RoundingMode.DOWN

/**
 * Количество: число вместе с единицей измерения.
 *
 * Неразделимы, поэтому сложить таблетки с миллилитрами нельзя даже случайно. Нормализация
 * масштаба живёт здесь же — ненормализованной величины не существует.
 */
class Quantity(amount: BigDecimal, val unit: QuantityUnit) : Comparable<Quantity> {

    val amount: BigDecimal = amount.setScale(QUANTITY_SCALE, QUANTITY_ROUNDING)

    init {
        if (this.amount.signum() < 0) throw NegativeQuantity()
    }

    val isZero: Boolean get() = amount.signum() == 0

    val isPositive: Boolean get() = amount.signum() > 0

    operator fun plus(other: Quantity): Quantity = Quantity(amount + sameUnit(other), unit)

    operator fun minus(other: Quantity): Quantity = Quantity(amount - sameUnit(other), unit)

    override fun compareTo(other: Quantity): Int = amount.compareTo(sameUnit(other))

    private fun sameUnit(other: Quantity): BigDecimal {
        if (unit != other.unit) throw QuantityUnitMismatch()
        return other.amount
    }

    /** Значение: равны те, у кого совпадают число и единица, а не запись числа. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Quantity && unit == other.unit && amount.compareTo(other.amount) == 0)

    override fun hashCode(): Int = 31 * unit.hashCode() + amount.stripTrailingZeros().hashCode()

    override fun toString(): String = "$amount ${unit.name}"
}

/**
 * Единица измерения из общего справочника.
 *
 * По идентификатору, а не по имени: та же таблица стоит за каталогом, поэтому «шт» у заведённой
 * руками упаковки и «шт» у карточки каталога — одна единица, а не две одинаковые строки.
 */
data class QuantityUnit(
    val id: Uuid,
    val name: String
)
