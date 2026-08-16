package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Арифметика дробных количеств на настоящем PostgreSQL.
 *
 * Смысл перехода на `NUMERIC(19,6)`: остаток и планы — это обещания о реальном препарате, и
 * треть таблетки не должна ни исчезать, ни появляться из двоичной погрешности.
 */
@PostgresIntegrationTest
@Transactional
class FractionalQuantityTest {

    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var usingRepository: UsingRepository
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private fun third(of: String): BigDecimal =
        BigDecimal(of).divide(BigDecimal(3), 6, RoundingMode.DOWN)

    @Test
    fun `приём третями расходует остаток без потерь`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 1.0)
        usingService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(1.0)))
        dbHelper.flushAndClear()

        val third = third("1")   // 0.333333
        usingService.recordIntake(alice.id, drug.id, third)
        usingService.recordIntake(alice.id, drug.id, third)
        dbHelper.flushAndClear()

        // 1 - 2 * 0.333333 = 0.333334: остаток чуть больше трети, и он не потерян.
        assertQty(qty("0.333334"), dbHelper.drugQuantity(drug.id))

        // Третий приём забирает ровно остаток — препарат кончился.
        val last = dbHelper.drugQuantity(drug.id)!!
        val afterLast = usingService.recordIntake(alice.id, drug.id, last)
        dbHelper.flushAndClear()

        assertNull(afterLast, "план исчезает вместе с кончившимся препаратом")
        assertTrue(
            drugRepository.findById(drug.id).isEmpty,
            "препарат с нулевым остатком должен быть удалён"
        )
        assertTrue(
            usingRepository.findAllByUsingKeyDrugId(drug.id).isEmpty(),
            "планов не должно остаться"
        )
    }

    @Test
    fun `сумма планов не превышает остаток после пропорционального сжатия`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)

        val drug = dbHelper.freshDrug(kit, 10.0)
        usingService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(7.0)))
        usingService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(3.0)))
        dbHelper.flushAndClear()

        // Списываем треть остатка: коэффициент сжатия — бесконечная дробь.
        usingService.recordIntake(alice.id, drug.id, third("10"))
        dbHelper.flushAndClear()

        val remaining = dbHelper.drugQuantity(drug.id)!!
        val plansTotal = usingRepository.findAllByUsingKeyDrugId(drug.id)
            .fold(BigDecimal.ZERO) { sum, plan -> sum + plan.plannedAmount }

        assertTrue(
            plansTotal <= remaining,
            "сумма планов $plansTotal обязана не превышать остаток $remaining"
        )
        // При этом сжатие не должно съедать заметно больше младшего разряда на план.
        assertTrue(
            remaining - plansTotal < qty("0.00001"),
            "сжатие потеряло слишком много: остаток $remaining, планы $plansTotal"
        )
    }

    @Test
    fun `пропорциональное сжатие сохраняет точные доли`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)

        val drug = dbHelper.freshDrug(kit, 90.0)
        usingService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        usingService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(60.0)))
        dbHelper.flushAndClear()

        // Незапланированный расход: остаток падает до 60 при сумме планов 90, поэтому планы
        // сжимаются с коэффициентом 60/90. Доли обязаны стать ровно 20 и 40, а не 19.999999.
        drugService.consumeDrug(drug.id, qty(30.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(40.0, dbHelper.userPlan(bob.id, drug.id))
    }
}
