package org.kert0n.medappserver.services.models

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Что происходит с планами, когда препарата стало меньше.
 *
 * Правило живёт в самом агрегате; здесь оно проверяется целиком, вместе с записью в базу:
 * пропорция считается по всем планам сразу, и результат должен уложиться в остаток.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlanReconciliationTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var treatmentPlanRepository: TreatmentPlanRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── остаток дошёл до нуля: препарат удаляется вместе с планами ──

    @Test
    fun `drug deleted when quantity reaches zero`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertNull(drugService.findById(drug.id))
        assertEquals(0, treatmentPlanRepository.findAllByPlanKeyDrugId(drug.id).size)
    }

    // ── сумма планов укладывается в остаток: пересчёта нет ──

    @Test
    fun `no scaling when planned within slack`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(20.0))
        drugService.createPlan(bob.id, drug.id, qty(20.0))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id))
    }

    // ── сумма планов больше остатка: пропорциональное сжатие ──

    @Test
    fun `proportional scaling when planned exceeds quantity`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(60.0))
        drugService.createPlan(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // Consume 50 → quantity=50, factor=50/100=0.5
        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id)!!)
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!)
        assertQty(50.0, dbHelper.totalPlanned(drug.id)!!)
    }

    // ── соотношение планов сохраняется ──

    @Test
    fun `asymmetric plans preserve ratio after scaling`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(60.0))
        drugService.createPlan(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        drugService.consume(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!
        assertQty(3.0 / 2.0, alicePlan / bobPlan)
    }
}
