package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.controller.UsingCreateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
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
class QuantityReductionServiceTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
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
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(50.0)))
        dbHelper.flushAndClear()

        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        assertNull(drugRepository.findByIdOrNull(drug.id))
        assertEquals(0, usingRepository.findAllByUsingKeyDrugId(drug.id).size)
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(20.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(20.0)))
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(60.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        // Consume 50 → quantity=50, factor=50/100=0.5
        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(60.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(40.0)))
        dbHelper.flushAndClear()

        drugService.consumeDrug(drug.id, qty(50.0), alice.id)
        dbHelper.flushAndClear()

        val alicePlan = dbHelper.userPlan(alice.id, drug.id)!!
        val bobPlan = dbHelper.userPlan(bob.id, drug.id)!!
        assertQty(3.0 / 2.0, alicePlan / bobPlan)
    }
}
