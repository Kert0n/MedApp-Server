package org.kert0n.medappserver.db.model

import org.kert0n.medappserver.domain.quantity.isPositive as domainIsPositive
import org.kert0n.medappserver.domain.quantity.isZero as domainIsZero
import org.kert0n.medappserver.domain.quantity.toQuantityScale as domainToQuantityScale
import java.math.BigDecimal
import java.math.RoundingMode

@Deprecated("Use domain.quantity.QUANTITY_SCALE")
const val QUANTITY_SCALE: Int = org.kert0n.medappserver.domain.quantity.QUANTITY_SCALE

@Deprecated("Use domain.quantity.QUANTITY_ROUNDING")
val QUANTITY_ROUNDING: RoundingMode = org.kert0n.medappserver.domain.quantity.QUANTITY_ROUNDING

@Deprecated("Use domain.quantity.toQuantityScale")
fun BigDecimal.toQuantityScale(): BigDecimal = domainToQuantityScale()

@Deprecated("Use domain.quantity.isZero")
fun BigDecimal.isZero(): Boolean = domainIsZero()

@Deprecated("Use domain.quantity.isPositive")
fun BigDecimal.isPositive(): Boolean = domainIsPositive()
