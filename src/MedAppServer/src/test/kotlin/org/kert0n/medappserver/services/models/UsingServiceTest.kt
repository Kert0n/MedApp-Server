package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.api.UsingUpdateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UsingServiceTest {

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var usingService: UsingService
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
    fun `findAllByUser returns usings for user`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByUser(alice.id).size)
    }

    @Test
    fun `findAllByDrug returns usings for drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByDrug(drug.id).size)
    }

    // ── findByUserAndDrug ──

    @Test
    fun `findByUserAndDrug throws NOT_FOUND when no plan exists`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            usingService.findByUserAndDrug(alice.id, drug.id)
        }
    }

    // ── deleteAllByUserIdInMedkit ──

    @Test
    fun `deleteAllByUserIdInMedkit removes plans for user in specific medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByUser(alice.id).size)

        usingService.deleteAllByUserIdInMedkit(alice.id, kit.id)
        dbHelper.flushAndClear()

        assertEquals(0, usingService.findAllByUser(alice.id).size)
    }

    // ── recordIntake ──

    @Test
    fun `recordIntake reduces both drug quantity and planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val updated = drugService.applyIntake(alice.id, drug.id, qty(10.0))
        assertNotNull(updated)
        assertQty(20.0, updated.plannedAmount)
        assertQty(90.0, drugService.findById(drug.id).quantity)
    }

    @Test
    fun `recordIntake throws when exceeding planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val ex = assertFailsWith<ResponseStatusException> {
            drugService.applyIntake(alice.id, drug.id, qty(15.0))
        }
        assertTrue(ex.reason!!.contains("exceeds planned amount"))
    }

    @Test
    fun `recordIntake throws when exceeding drug quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        // Artificially corrupt DB state to simulate race condition
        val directDrug = drugService.findByIdForUserForUpdate(drug.id, alice.id)
        directDrug.quantity = qty(2.0)
        drugRepository.saveAndFlush(directDrug)

        val ex = assertFailsWith<ResponseStatusException> {
            drugService.applyIntake(alice.id, drug.id, qty(5.0))
        }
        assertTrue(ex.reason!!.contains("Insufficient drug quantity"))
    }

    @Test
    fun `recordIntake exactly consuming planned amount deletes plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val result = drugService.applyIntake(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertNull(result)
        assertNull(dbHelper.userPlan(alice.id, drug.id))
        assertQty(10.0, dbHelper.drugQuantity(drug.id))
    }

    // ── deleteTreatmentPlan ──

    @Test
    fun `deleteTreatmentPlan removes plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        usingService.deleteTreatmentPlan(alice.id, drug.id)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            usingService.findByUserAndDrug(alice.id, drug.id)
        }
    }

    // ── toUsingDTO ──

    @Test
    fun `toUsingDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val using = treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val dto = using.toDto()
        assertEquals(alice.id, dto.userId)
        assertEquals(drug.id, dto.drugId)
        assertQty(30.0, dto.plannedAmount)
    }
}
