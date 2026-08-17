package org.kert0n.medappserver.domain

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty

/**
 * Инварианты упаковки.
 *
 * Ни Spring, ни базы, ни единой сущности: домен — неизменяемые значения, а участники здесь
 * всего лишь идентификаторы.
 *
 * Проверять тут больше нечего про брони, и это главный итог смены концепции: упаковка о них не
 * знает. Ушли пропорциональный пересчёт, «сумма планов не больше остатка», приём по плану — со
 * всеми правилами, которые сервер применял за владельца брони.
 */
class DrugAggregateTest {

    private val kit = UUID.randomUUID()
    private val pills = QuantityUnit(UUID.randomUUID(), "pills")
    private val millilitres = QuantityUnit(UUID.randomUUID(), "ml")

    private fun q(value: Double, unit: QuantityUnit = pills) = Quantity(qty(value), unit)

    private fun drug(quantity: Double): Drug = Drug(medKitId = kit, name = "Aspirin", quantity = q(quantity))

    // ── Создание ─────────────────────────────────────────────────────────────────

    @Test
    fun `упаковка не заводится с нулевым количеством`() {
        assertFailsWith<InvalidQuantity> { drug(0.0) }
    }

    /** Отрицательное отвергает сама величина — до того, как её увидит упаковка. */
    @Test
    fun `упаковка не заводится с отрицательным количеством`() {
        assertFailsWith<NegativeQuantity> { drug(-1.0) }
    }

    // ── Количество ───────────────────────────────────────────────────────────────

    /**
     * Это исправление учёта, а не пополнение: пересчитал пачку и увидел другое число.
     * Пополнения у упаковки не бывает — новая пачка это новая упаковка.
     */
    @Test
    fun `количество исправляется в обе стороны`() {
        assertQty(20.0, drug(10.0).changeQuantityTo(q(20.0)).quantity)
        assertQty(5.0, drug(10.0).changeQuantityTo(q(5.0)).quantity)
    }

    @Test
    fun `исправление нулём отвергается`() {
        assertFailsWith<InvalidQuantity> { drug(10.0).changeQuantityTo(q(0.0)) }
    }

    @Test
    fun `величина в чужой единице отвергается`() {
        assertFailsWith<QuantityUnitMismatch> { drug(10.0).changeQuantityTo(q(5.0, millilitres)) }
        assertFailsWith<QuantityUnitMismatch> { drug(10.0).consume(q(1.0, millilitres)) }
    }

    @Test
    fun `перевеска меняет подпись, а не число`() {
        val relabelled = drug(10.0).relabelUnitTo(millilitres)

        assertEquals(millilitres, relabelled.quantity.unit)
        assertQty(10.0, relabelled.quantity)
    }

    // ── Съеденное ────────────────────────────────────────────────────────────────

    @Test
    fun `списание уменьшает пачку`() {
        assertQty(7.0, drug(10.0).consume(q(3.0))!!.quantity)
    }

    /**
     * Не защита от клиента, а физика: столько таблеток в пачке не было. Пополнить упаковку
     * нельзя, поэтому и перерасхода не бывает.
     */
    @Test
    fun `списать больше, чем в пачке, нельзя`() {
        assertFailsWith<InsufficientStock> { drug(10.0).consume(q(11.0)) }
    }

    @Test
    fun `списание нуля отвергается`() {
        assertFailsWith<InvalidQuantity> { drug(10.0).consume(q(0.0)) }
    }

    /** Пустая пачка выбрасывается: `null` означает «уничтожить», а не «не найдено». */
    @Test
    fun `списание всей пачки её исчерпывает`() {
        assertNull(drug(10.0).consume(q(10.0)))
    }

    // ── Переезд ──────────────────────────────────────────────────────────────────

    /**
     * Судьбу броней упаковка не решает: они в чужих агрегатах, и убирает их тот, кто знает
     * состав целевой аптечки.
     */
    @Test
    fun `переезд меняет только аптечку`() {
        val target = UUID.randomUUID()
        val moved = drug(10.0).moveTo(target)

        assertEquals(target, moved.medKitId)
        assertQty(10.0, moved.quantity)
    }

    // ── Идентичность ─────────────────────────────────────────────────────────────

    @Test
    fun `два состояния одной упаковки равны`() {
        val before = drug(10.0)
        val after = before.consume(q(3.0))!!

        assertEquals(before, after)
        assertNotEquals(before, drug(10.0))
    }
}
