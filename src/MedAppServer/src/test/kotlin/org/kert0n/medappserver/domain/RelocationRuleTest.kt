package org.kert0n.medappserver.domain

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.qty

/**
 * Правило переезда — в домене, а не только в запросе, который его исполняет.
 *
 * Аптечка — физическое хранилище, бронь — назначение. Коробку переставили: допущен к новому
 * месту — назначение осталось, не допущен — снято. Массовое удаление в хранилище это та же
 * мысль на SQL, и последний тест здесь сторожит их согласие.
 */
class RelocationRuleTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val drugId = UUID.randomUUID()
    private val unit = QuantityUnit(UUID.randomUUID(), "шт")

    private fun reservationOf(userId: UUID) = Reservation(userId, drugId, Quantity(qty(20.0), unit))

    @Test
    fun `назначение переживает переезд в доступное хранилище`() {
        val target = MedKit(members = setOf(alice, bob))

        assertTrue(reservationOf(bob).survivesRelocationTo(target))
    }

    @Test
    fun `назначение не переживает переезд в недоступное`() {
        val target = MedKit(members = setOf(alice))

        assertFalse(reservationOf(bob).survivesRelocationTo(target))
    }

    /**
     * Согласие правила с тем, как оно исполняется.
     *
     * Массовый запрос снимает брони по `userId NOT IN :members` — то есть по отрицанию этого
     * предиката. Разойдутся — и правило начнёт значить одно, а делать другое.
     */
    @Test
    fun `массовое снятие совпадает с предикатом`() {
        val target = MedKit(members = setOf(alice))
        val reservations = listOf(reservationOf(alice), reservationOf(bob))

        val keptByRule = reservations.filter { it.survivesRelocationTo(target) }
        val keptByQuery = reservations.filter { it.userId in target.members }

        assertEquals(keptByQuery, keptByRule, "предикат домена и условие запроса разошлись")
    }
}
