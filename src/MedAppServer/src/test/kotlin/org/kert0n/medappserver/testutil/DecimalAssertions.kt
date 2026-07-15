package org.kert0n.medappserver.testutil

import java.math.BigDecimal
import kotlin.test.fail

fun assertDecimalEquals(expected: BigDecimal, actual: BigDecimal?, message: String? = null) {
    if (actual == null || expected.compareTo(actual) != 0) {
        fail(message ?: "Expected $expected, actual $actual")
    }
}

fun assertDecimalEquals(
    expected: BigDecimal,
    actual: BigDecimal,
    tolerance: BigDecimal,
    message: String? = null
) {
    if ((expected - actual).abs() > tolerance) {
        fail(message ?: "Expected $expected ± $tolerance, actual $actual")
    }
}
