package org.kert0n.medappserver.testutil

import org.kert0n.medappserver.domain.toQuantityScale
import java.math.BigDecimal
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Количество из литерала теста. Через [BigDecimal.valueOf], а не через конструктор: `BigDecimal(0.1)`
 * даёт 0.1000000000000000055511151231257827, то есть ровно ту двоичную ошибку, ради ухода от
 * которой количества и переведены на numeric.
 */
fun qty(value: Double): BigDecimal = BigDecimal.valueOf(value).toQuantityScale()

/** Количество из точной строки — для значений, которые не выражаются double без потерь. */
fun qty(value: String): BigDecimal = BigDecimal(value).toQuantityScale()

/**
 * Сравнение количеств по значению. `assertEquals` здесь не годится: у BigDecimal он учитывает
 * масштаб, поэтому `10` и `10.000000` считались бы разными.
 */
fun assertQty(expected: BigDecimal, actual: BigDecimal?, message: String? = null) {
    assertNotNull(actual, message ?: "ожидалось количество $expected, получено null")
    assertTrue(
        expected.compareTo(actual) == 0,
        message ?: "ожидалось количество $expected, получено $actual"
    )
}

fun assertQty(expected: Double, actual: BigDecimal?, message: String? = null) =
    assertQty(qty(expected), actual, message)
