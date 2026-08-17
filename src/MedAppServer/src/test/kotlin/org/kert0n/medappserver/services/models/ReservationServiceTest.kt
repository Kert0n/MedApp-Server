package org.kert0n.medappserver.services.models

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Здесь остались только чтения планов.
 *
 * Создание, изменение, удаление и приём переехали в агрегат `Drug` и проверяются в
 * [DrugServiceTest]: у них общий инвариант — сумма планов не больше остатка, — и врозь он
 * не формулируется.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationServiceTest {

    @Autowired
    private lateinit var reservationService: ReservationService
    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `findAllByUser returns treatment plans for user`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        reservationService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, reservationService.ofUser(alice.id).size)
    }

    @Test
    fun `findAllByDrug returns treatment plans for drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        reservationService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertQty(10.0, dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `requirePlan throws NOT_FOUND when no plan exists`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            reservationService.require(alice.id, drug.id)
        }
    }

    @Test
    fun `view carries the planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        reservationService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val dto = reservationService.require(alice.id, drug.id).toDto()
        assertEquals(drug.id, dto.drugId)
        assertQty(30.0, dto.plannedAmount)
    }
}
