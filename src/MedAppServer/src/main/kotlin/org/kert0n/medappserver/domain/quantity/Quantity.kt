package org.kert0n.medappserver.domain.quantity

import java.math.BigDecimal
import java.math.RoundingMode

const val QUANTITY_PRECISION: Int = 19
const val QUANTITY_SCALE: Int = 6

val QUANTITY_ROUNDING: RoundingMode = RoundingMode.HALF_UP

fun BigDecimal.toQuantityScale(roundingMode: RoundingMode = QUANTITY_ROUNDING): BigDecimal =
    setScale(QUANTITY_SCALE, roundingMode)

fun BigDecimal.isZero(): Boolean = signum() == 0

fun BigDecimal.isPositive(): Boolean = signum() > 0
