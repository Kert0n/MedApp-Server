package org.kert0n.medappserver.integration

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.orchestrator.DrugDisposal
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Арифметика дробных количеств на настоящем PostgreSQL.
 *
 * Смысл `NUMERIC(19,6)`: остаток и брони — обещания о реальном препарате, и треть таблетки не
 * должна ни исчезать, ни появляться из двоичной погрешности.
 */
@PostgresIntegrationTest
@Transactional
class FractionalQuantityTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var disposal: DrugDisposal
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private fun third(of: String): BigDecimal =
        BigDecimal(of).divide(BigDecimal(3), 6, RoundingMode.DOWN)

    @Test
    fun `приём третями расходует остаток без потерь`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 1.0)
        dbHelper.reserve(alice.id, drug.id, qty(1.0))
        dbHelper.flushAndClear()

        val third = third("1")   // 0.333333
        drugService.consume(drugService.get(drug.id, alice.id), third, dbHelper.drugVersion(drug.id))
        drugService.consume(drugService.get(drug.id, alice.id), third, dbHelper.drugVersion(drug.id))
        dbHelper.flushAndClear()

        // 1 - 2 * 0.333333 = 0.333334: остаток чуть больше трети, и он не потерян.
        assertQty(qty("0.333334"), dbHelper.drugQuantity(drug.id))

        // Третий приём забирает ровно остаток — препарат кончился.
        val last = dbHelper.drugQuantity(drug.id)!!
        val afterLast = disposal.consume(drugService.get(drug.id, alice.id), last, dbHelper.drugVersion(drug.id))
        dbHelper.flushAndClear()

        assertNull(afterLast, "план исчезает вместе с кончившимся препаратом")
        assertTrue(
            dbHelper.drug(drug.id) == null,
            "препарат с нулевым остатком должен быть удалён"
        )
        assertQty(0.0, dbHelper.reservedOnDrug(drug.id), "броней не должно остаться")
    }


}
