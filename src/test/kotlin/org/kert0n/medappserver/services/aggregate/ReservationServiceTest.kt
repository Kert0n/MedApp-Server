package org.kert0n.medappserver.services.aggregate

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

/** Чтения броней: своих, по упаковке и с 404 на отсутствующей. */
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
    fun `ofUser returns the caller's reservations`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, reservationService.ofUser(alice.id).size)
    }

    @Test
    fun `a reservation lands on the drug it was made for`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertQty(10.0, dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `get throws NOT_FOUND when there is no reservation`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            reservationService.get(alice.id, drug.id)
        }
    }

    @Test
    fun `view carries the reserved amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val dto = reservationService.get(alice.id, drug.id).toDto()
        assertEquals(drug.id, dto.drugId)
        assertQty(30.0, dto.amount)
    }
}
