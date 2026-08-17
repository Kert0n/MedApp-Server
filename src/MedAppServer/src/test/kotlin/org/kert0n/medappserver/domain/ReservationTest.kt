package org.kert0n.medappserver.domain

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty

/**
 * Инварианты брони.
 *
 * Их всего один — количество строго положительно, — и это не бедность модели, а её суть.
 * Главное здесь то, чего нет: **ограничения сверху**. Бронь может превышать остаток упаковки,
 * потому что решение, чем поступиться, принадлежит её владельцу, а не серверу.
 */
class ReservationTest {

    private val alice = UUID.randomUUID()
    private val drug = UUID.randomUUID()
    private val pills = QuantityUnit(UUID.randomUUID(), "pills")
    private val millilitres = QuantityUnit(UUID.randomUUID(), "ml")

    private fun q(value: Double, unit: QuantityUnit = pills) = Quantity(qty(value), unit)

    private fun reservation(amount: Double) = Reservation(alice, drug, q(amount))

    @Test
    fun `бронь не заводится с нулём`() {
        assertFailsWith<InvalidQuantity> { reservation(0.0) }
    }

    @Test
    fun `бронь не заводится с отрицательным количеством`() {
        assertFailsWith<NegativeQuantity> { reservation(-1.0) }
    }

    /** Сорок из пачки на тридцать — нормальное состояние: ужать бронь сервер не вправе. */
    @Test
    fun `бронь не ограничена размером упаковки`() {
        assertQty(40.0, reservation(40.0).amount)
        assertQty(1000.0, reservation(1.0).changeTo(q(1000.0)).amount)
    }

    @Test
    fun `изменение брони нулём отвергается`() {
        assertFailsWith<InvalidQuantity> { reservation(10.0).changeTo(q(0.0)) }
    }

    @Test
    fun `бронь в чужой единице отвергается`() {
        assertFailsWith<QuantityUnitMismatch> { reservation(10.0).changeTo(q(5.0, millilitres)) }
    }

    @Test
    fun `бронь принадлежит паре человека и упаковки`() {
        val changed = reservation(10.0).changeTo(q(15.0))

        assertEquals(alice, changed.userId)
        assertEquals(drug, changed.drugId)
    }
}
