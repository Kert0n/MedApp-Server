package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Заявленное бронями складывается снаружи упаковки.
 *
 * Проверяется, что сумма верна и что чужая бронь в неё не попадает.
 */
@PostgresIntegrationTest
@Transactional
class ReadProjectionTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `сумма планов складывается по всем участникам`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(kit.id, alice.id), alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(30.0))
        reservationService.create(drugService.get(drug.id, bob.id), bob.id, qty(20.0))
        dbHelper.flushAndClear()

        val view = drugService.get(drug.id, alice.id)

        assertQty(100.0, view.quantity)
        // Заявленное считается снаружи: упаковка про брони не знает и знать не должна.
        assertQty(50.0, dbHelper.reservedOnDrug(drug.id))
    }

    @Test
    fun `упаковка без броней отдаёт нулевую сумму, а не отсутствие строки`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 7.0)
        dbHelper.flushAndClear()

        val view = drugService.get(drug.id, alice.id)

        assertQty(7.0, view.quantity)
        assertQty(0.0, dbHelper.reservedOnDrug(drug.id))
    }

    @Test
    fun `чужой препарат не читается`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertFailsWith<NotAMember>("чужая аптечка не должна читаться") { drugService.get(drug.id, eve.id) }
    }

    @Test
    fun `снимок собирает препараты всех аптечек пользователя одним запросом`() {
        val alice = dbHelper.freshUser("alice")
        val first = medKitService.create(alice.id)
        val second = medKitService.create(alice.id)
        val outsider = dbHelper.freshUser("outsider")
        val foreign = medKitService.create(outsider.id)

        dbHelper.freshDrug(first.id, 1.0)
        dbHelper.freshDrug(first.id, 2.0)
        dbHelper.freshDrug(second.id, 3.0)
        dbHelper.freshDrug(foreign.id, 4.0)
        dbHelper.flushAndClear()

        val views = drugService.allOf(alice.id)

        assertEquals(3, views.size, "видны препараты обеих своих аптечек и только их")
        assertEquals(2, views.count { it.medKitId == first.id })
        assertEquals(1, views.count { it.medKitId == second.id })
        assertTrue(views.none { it.medKitId == foreign.id }, "чужая аптечка в снимок не попадает")
    }

    @Test
    fun `план читается только своим владельцем`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(kit.id, alice.id), alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(30.0))
        dbHelper.flushAndClear()

        assertQty(30.0, reservationService.get(alice.id, drug.id).amount)
        // Пачка общая, бронь личная: Боб видит упаковку, но не чужую бронь.
        assertFailsWith<NoSuchReservation>("чужой план не читается") { reservationService.get(bob.id, drug.id) }
    }
}
