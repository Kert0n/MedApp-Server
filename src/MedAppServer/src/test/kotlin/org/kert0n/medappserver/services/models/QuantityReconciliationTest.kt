package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuantityReconciliationTest {

    @Autowired
    private lateinit var drugCommands: DrugCommandService
    @Autowired
    private lateinit var medKitFixture: MedKitFixture
    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var usingService: UsingService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var usingRepository: UsingRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── handleQuantityReduction: quantity == 0 → drug deleted ──

    @Test
    fun `drug deleted when quantity reaches zero`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        drugCommands.consume(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        assertNull(drugRepository.findByIdOrNull(drug.id))
        assertEquals(0, usingRepository.findAllByDrugId(drug.id).size)
    }

    // ── handleQuantityReduction: totalPlanned <= quantity → no scaling ──

    @Test
    fun `no scaling when planned within slack`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitFixture.createNew(alice.id)
        medKitFixture.joinMedKitByKey(medKitFixture.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(20.0))
        treatmentPlanService.create(bob.id, drug.id, qty(20.0))
        dbHelper.flushAndClear()

        drugCommands.consume(alice.id, drug.id, qty(50.0))
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
        val kit = medKitFixture.createNew(alice.id)
        medKitFixture.joinMedKitByKey(medKitFixture.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(60.0))
        treatmentPlanService.create(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // Consume 50 → quantity=50, factor=50/100=0.5
        drugCommands.consume(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id))
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id))
        assertQty(20.0, dbHelper.userPlan(bob.id, drug.id))
        assertQty(50.0, dbHelper.totalPlanned(drug.id))
    }

    // ── handleUsingReduction: ratio preserved ──

    @Test
    fun `asymmetric plans preserve ratio after scaling`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitFixture.createNew(alice.id)
        medKitFixture.joinMedKitByKey(medKitFixture.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(60.0))
        treatmentPlanService.create(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        drugCommands.consume(alice.id, drug.id, qty(50.0))
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!
        assertQty(3.0 / 2.0, alicePlan / bobPlan)
    }
}
