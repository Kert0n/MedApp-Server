package org.kert0n.medappserver.db.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Scale для остатков и планов, соответствующий `NUMERIC(19,6)` в базе.
 */
const val QUANTITY_SCALE: Int = 6

val QUANTITY_ROUNDING: RoundingMode = RoundingMode.HALF_UP

/**
 * Нормализует результат арифметики до представления колонки.
 * Сеттеры [Drug] и [Using] применяют это правило ко всем изменениям количества.
 */
fun BigDecimal.toQuantityScale(): BigDecimal = setScale(QUANTITY_SCALE, QUANTITY_ROUNDING)

/**
 * Сравнение с нулём без учёта scale [BigDecimal].
 */
fun BigDecimal.isZero(): Boolean = signum() == 0

/** Строго положительное значение: `> 0`. */
fun BigDecimal.isPositive(): Boolean = signum() > 0
