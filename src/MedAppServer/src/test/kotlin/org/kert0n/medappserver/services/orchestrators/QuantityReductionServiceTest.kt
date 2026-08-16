package org.kert0n.medappserver.services.orchestrators

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuantityReductionServiceTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var treatmentPlanRepository: TreatmentPlanRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── handleQuantityReduction: quantity == 0 → drug deleted ──

    @Test
    fun `drug deleted when quantity reaches zero`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(50.0)))
        dbHelper.flushAndClear()

        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertNull(drugRepository.findByIdOrNull(drug.id))
        assertEquals(0, treatmentPlanRepository.findAllByPlanKeyDrugId(drug.id).size)
    }

    // ── handleQuantityReduction: totalPlanned <= quantity → no scaling ──

    @Test
    fun `no scaling when planned within slack`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(20.0)))
        treatmentPlanService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(20.0)))
        dbHelper.flushAndClear()

        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(20.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id))
    }

    // ── handleQuantityReduction: totalPlanned > quantity → proportional scaling ──

    @Test
    fun `proportional scaling when planned exceeds quantity`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(60.0)))
        treatmentPlanService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        // Consume 50 → quantity=50, factor=50/100=0.5
        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id)!!)
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id)!!)
        assertQty(50.0, dbHelper.totalPlanned(drug.id)!!)
    }

    // ── handlePlanReduction: ratio preserved ──

    @Test
    fun `asymmetric plans preserve ratio after scaling`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(60.0)))
        treatmentPlanService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!
        assertQty(3.0 / 2.0, alicePlan / bobPlan)
    }
}
