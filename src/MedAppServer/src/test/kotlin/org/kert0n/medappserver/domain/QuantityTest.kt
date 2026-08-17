package org.kert0n.medappserver.domain

import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Контракт величины: масштаб и единица принадлежат ей самой, а не сеттерам отображения, — и
 * проверяются здесь, без базы.
 */
class QuantityTest {

    private val tablets = QuantityUnit(UUID.randomUUID(), "таб")
    private val millilitres = QuantityUnit(UUID.randomUUID(), "мл")

    private fun q(value: String, unit: QuantityUnit = tablets) = Quantity(BigDecimal(value), unit)

    @Test
    fun `величина нормализуется до масштаба колонки`() {
        assertEquals(QUANTITY_SCALE, q("2.5").amount.scale())
    }

    @Test
    fun `лишние знаки отбрасываются, а не округляются вверх`() {
        assertEquals(
            BigDecimal("1.234567"), q("1.23456789").amount,
            "округление вверх приписало бы препарату количество, которого нет"
        )
    }

    @Test
    fun `отрицательной величины не бывает`() {
        assertFailsWith<NegativeQuantity> { q("-0.000001") }
        assertFailsWith<NegativeQuantity> { q("5") - q("6") }
    }

    @Test
    fun `величины в разных единицах не складываются и не сравниваются`() {
        assertFailsWith<QuantityUnitMismatch> { q("5") + q("5", millilitres) }
        assertFailsWith<QuantityUnitMismatch> { q("5") - q("1", millilitres) }
        assertFailsWith<QuantityUnitMismatch> { q("5") > q("1", millilitres) }
    }

    @Test
    fun `равенство считает число и единицу, а не запись числа`() {
        assertEquals(q("10"), q("10.000000"))
        assertEquals(q("10").hashCode(), q("10.000000").hashCode())
        assertTrue(q("10") != q("10", millilitres), "одинаковые числа в разных единицах — разные величины")
    }
}
