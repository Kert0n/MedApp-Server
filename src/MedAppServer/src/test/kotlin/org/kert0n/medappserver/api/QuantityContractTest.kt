package org.kert0n.medappserver.api

import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Граница в контракте — та же, что у колонки.
 *
 * Число в схемах приходится писать строкой: springdoc не переносит `@Digits` в опубликованный
 * контракт, а `@Schema` не умеет считать. Дубль осознанный, но он обязан быть проверяемым —
 * иначе правка `numeric(19, 6)` тихо разойдётся с тем, что обещано клиенту.
 */
class QuantityContractTest {

    @Test
    fun `граница схемы совпадает с разрядностью колонки`() {
        val digitsBeforePoint = QUANTITY_PRECISION - QUANTITY_SCALE
        val expected = "9".repeat(digitsBeforePoint) + "." + "9".repeat(QUANTITY_SCALE)

        assertEquals(
            expected,
            QUANTITY_MAX,
            "максимум схемы разошёлся с numeric($QUANTITY_PRECISION, $QUANTITY_SCALE)"
        )
    }

    @Test
    fun `шаг схемы совпадает с масштабом колонки`() {
        assertEquals(
            BigDecimal.ONE.movePointLeft(QUANTITY_SCALE).toDouble(),
            QUANTITY_STEP,
            "шаг схемы разошёлся с масштабом колонки"
        )
    }
}
