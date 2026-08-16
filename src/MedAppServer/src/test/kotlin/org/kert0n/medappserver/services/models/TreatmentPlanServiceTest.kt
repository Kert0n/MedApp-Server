package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.toDto
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TreatmentPlanServiceTest {

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── findAllByUser / findAllByDrug ──

    @Test
    fun `findAllByUser returns treatment plans for user`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, treatmentPlanService.findAllByUser(alice.id).size)
    }

    @Test
    fun `findAllByDrug returns treatment plans for drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, treatmentPlanService.findAllByDrug(drug.id).size)
    }

    // ── findByUserAndDrug ──

    @Test
    fun `findByUserAndDrug throws NOT_FOUND when no plan exists`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.requirePlan(alice.id, drug.id)
        }
    }

    // ── deleteAllByUserIdInMedkit ──

    @Test
    fun `deleteAllByUserIdInMedkit removes plans for user in specific medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, treatmentPlanService.findAllByUser(alice.id).size)

        treatmentPlanService.deleteAllByUserIdInMedkit(alice.id, kit.id)
        dbHelper.flushAndClear()

        assertEquals(0, treatmentPlanService.findAllByUser(alice.id).size)
    }

    // ── createTreatmentPlan ──

    @Test
    fun `createTreatmentPlan creates plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val plan = treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))

        assertQty(30.0, plan.plannedAmount)
        assertEquals(alice.id, plan.user.id)
        assertEquals(drug.id, plan.drug.id)
    }

    @Test
    fun `createTreatmentPlan throws CONFLICT for duplicate`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(20.0)))
        }
    }

    @Test
    fun `createTreatmentPlan throws when exceeding available quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(100.0)))
        }
    }

    // ── updateTreatmentPlan ──

    @Test
    fun `updateTreatmentPlan updates planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val updated = treatmentPlanService.updateTreatmentPlan(alice.id, drug.id, TreatmentPlanPatchRequest(qty(50.0)))
        assertQty(50.0, updated.plannedAmount)
    }

    @Test
    fun `updateTreatmentPlan throws when exceeding available quantity`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(50.0)))
        treatmentPlanService.createTreatmentPlan(bob.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        // Bob tries to increase to 60 but only 100 - 50 = 50 available for him
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.updateTreatmentPlan(bob.id, drug.id, TreatmentPlanPatchRequest(qty(60.0)))
        }
    }

    // ── recordIntake ──

    @Test
    fun `recordIntake reduces both drug quantity and planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val updated = treatmentPlanService.recordIntake(alice.id, drug.id, qty(10.0))
        assertNotNull(updated)
        assertQty(20.0, updated.plannedAmount)
        assertQty(90.0, drugService.requireById(drug.id).quantity)
    }

    @Test
    fun `recordIntake throws when exceeding planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        val ex = assertFailsWith<ResponseStatusException> {
            treatmentPlanService.recordIntake(alice.id, drug.id, qty(15.0))
        }
        assertTrue(ex.reason!!.contains("exceeds planned amount"))
    }

    @Test
    fun `recordIntake throws when exceeding drug quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        // Artificially corrupt DB state to simulate race condition
        val directDrug = drugService.lockAccessible(drug.id, alice.id)
        directDrug.quantity = qty(2.0)
        drugRepository.saveAndFlush(directDrug)

        val ex = assertFailsWith<ResponseStatusException> {
            treatmentPlanService.recordIntake(alice.id, drug.id, qty(5.0))
        }
        assertTrue(ex.reason!!.contains("Insufficient drug quantity"))
    }

    @Test
    fun `recordIntake exactly consuming planned amount deletes plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        val result = treatmentPlanService.recordIntake(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertNull(result)
        assertNull(dbHelper.userPlan(alice.id, drug.id))
        assertQty(10.0, dbHelper.drugQuantity(drug.id)!!)
    }

    // ── deleteTreatmentPlan ──

    @Test
    fun `deleteTreatmentPlan removes plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        treatmentPlanService.deleteTreatmentPlan(alice.id, drug.id)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.requirePlan(alice.id, drug.id)
        }
    }

    // ── toTreatmentPlanDTO ──

    @Test
    fun `toTreatmentPlanDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val plan = treatmentPlanService.createTreatmentPlan(alice.id, TreatmentPlanCreateRequest(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val dto = treatmentPlanService.requireView(alice.id, drug.id).toDto()
        assertEquals(drug.id, dto.drugId)
        assertQty(30.0, dto.plannedAmount)
    }
}
