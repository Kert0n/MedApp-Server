package org.kert0n.medappserver.api

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Разрядность в контракте — та же, что у колонки.
 *
 * Образец в схемах приходится писать руками: springdoc не переносит `@Digits` в опубликованный
 * контракт, а `@Schema` не умеет считать. Дубль неизбежен, и потому проверяется: иначе правка
 * `numeric(19, 6)` тихо разойдётся с тем, что обещано клиенту.
 */
class QuantityContractTest {

    @Test
    fun `образец схемы совпадает с разрядностью колонки`() {
        val digitsBeforePoint = QUANTITY_PRECISION - QUANTITY_SCALE
        val expected = """^\d{1,$digitsBeforePoint}(\.\d{1,$QUANTITY_SCALE})?$"""

        assertEquals(
            expected,
            QUANTITY_PATTERN,
            "образец схемы разошёлся с numeric($QUANTITY_PRECISION, $QUANTITY_SCALE)"
        )
    }

    /**
     * Образец описывает ровно то, что помещается в колонку: значение на границе проходит,
     * лишний знак — нет. Без этого совпадение выше осталось бы совпадением двух строк, а не
     * утверждением о числах.
     */
    @Test
    fun `образец пропускает предельное значение и отсекает лишний знак`() {
        val regex = Regex(QUANTITY_PATTERN)
        val digitsBeforePoint = QUANTITY_PRECISION - QUANTITY_SCALE
        val largest = "9".repeat(digitsBeforePoint) + "." + "9".repeat(QUANTITY_SCALE)

        assertTrue(regex.matches(largest), "предельное значение колонки не прошло образец")
        assertTrue(regex.matches("0.5"), "половина таблетки не прошла образец")
        assertTrue(regex.matches("100"), "целое без дробной части не прошло образец")
        assertFalse(regex.matches(largest + "9"), "лишний знак после точки прошёл образец")
        assertFalse(regex.matches("9$largest"), "лишний знак до точки прошёл образец")
        assertFalse(
            regex.matches(BigDecimal.ONE.movePointLeft(QUANTITY_SCALE + 1).toPlainString()),
            "величина мельче масштаба колонки прошла образец"
        )
    }
}
