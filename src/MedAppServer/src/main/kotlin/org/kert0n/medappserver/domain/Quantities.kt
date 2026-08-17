package org.kert0n.medappserver.domain

import java.math.BigDecimal
import java.math.RoundingMode

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
 * Приводит результат арифметики к представлению колонки. Домен применяет это правило ко всем
 * изменениям, поэтому масштаб не расползается от умножений и делений.
 */
fun BigDecimal.toQuantityScale(): BigDecimal = setScale(QUANTITY_SCALE, QUANTITY_ROUNDING)

/** Сравнение с нулём без учёта масштаба: `0` и `0.000000` одинаково нулевые. */
fun BigDecimal.isZero(): Boolean = signum() == 0

/** Строго положительное значение. */
fun BigDecimal.isPositive(): Boolean = signum() > 0
