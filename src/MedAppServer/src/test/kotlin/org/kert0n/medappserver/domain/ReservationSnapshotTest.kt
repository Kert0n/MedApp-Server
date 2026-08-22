package org.kert0n.medappserver.domain

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test

/**
 * Заявленное на упаковку считается в одном месте.
 *
 * Раньше сумма и своя доля складывались в мапперe ответа: любой второй способ отдать пачку
 * означал бы второй подсчёт того же самого. Здесь закрепляется, что считает модель.
 */
class ReservationSnapshotTest {

    private val drug = Drug(
        id = Uuid.random(), medKitId = Uuid.random(), name = "Аспирин",
        quantity = Quantity(BigDecimal("10.0"), QuantityUnit(Uuid.random(), "шт"))
    )
    private val drugId = drug.id
    private val alice = Uuid.random()
    private val bob = Uuid.random()
    private val unit = QuantityUnit(Uuid.random(), "шт")

    @Test
    fun `сумма складывается по всем броням, а своя доля берётся из своей`() {
        val snapshot = ReservationSnapshot.of(
            drug,
            listOf(reservation(alice, "10.0"), reservation(bob, "5.5")),
            alice,
            version = 3
        )

        assertEquals(0, BigDecimal("15.500000").compareTo(snapshot.total), "сумма по всем броням")
        assertEquals(0, BigDecimal("10.000000").compareTo(snapshot.mine!!), "своя доля — только своя")
        assertEquals(3, snapshot.version)
    }

    /** Чужая пачка, на которую вызывающий не претендует: сумма есть, своей доли нет. */
    @Test
    fun `без своей брони доля отсутствует, а не равна нулю`() {
        val snapshot = ReservationSnapshot.of(drug, listOf(reservation(bob, "5.5")), alice, version = 1)

        assertNull(snapshot.mine, "ноль и «не претендую» — разные вещи")
        assertEquals(0, BigDecimal("5.500000").compareTo(snapshot.total))
    }

    /** Броней нет вовсе: сумма ноль, но версия у снимка всё равно своя. */
    @Test
    fun `пустой снимок несёт версию`() {
        val snapshot = ReservationSnapshot.empty(drug, version = 7)

        assertEquals(0, BigDecimal.ZERO.compareTo(snapshot.total))
        assertNull(snapshot.mine)
        assertEquals(7, snapshot.version)
    }

    private fun reservation(userId: Uuid, amount: String) =
        Reservation(userId, drugId, Quantity(BigDecimal(amount), unit))
}
