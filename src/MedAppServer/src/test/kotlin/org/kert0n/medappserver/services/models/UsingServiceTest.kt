package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.kert0n.medappserver.controller.UsingCreateDTO
import org.kert0n.medappserver.controller.UsingUpdateDTO
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByUser(alice.id).size)
    }

    @Test
    fun `findAllByDrug returns usings for drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, usingService.findAllByUser(alice.id).size)

        usingService.deleteAllByUserIdInMedkit(alice.id, kit.id)
        dbHelper.flushAndClear()

        assertEquals(0, usingService.findAllByUser(alice.id).size)
    }

    // ── createTreatmentPlan ──

    @Test
    fun `createTreatmentPlan creates plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val using = usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))

        assertQty(30.0, using.plannedAmount)
        assertEquals(alice.id, using.user.id)
        assertEquals(drug.id, using.drug.id)
    }

    @Test
    fun `createTreatmentPlan throws CONFLICT for duplicate`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(20.0)))
        }
    }

    @Test
    fun `createTreatmentPlan throws when exceeding available quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(100.0)))
        }
    }

    // ── updateTreatmentPlan ──

    @Test
    fun `updateTreatmentPlan updates planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val updated = usingService.updateTreatmentPlan(alice.id, drug.id, UsingUpdateDTO(qty(50.0)))
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(50.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        // Bob tries to increase to 60 but only 100 - 50 = 50 available for him
        assertFailsWith<ResponseStatusException> {
            usingService.updateTreatmentPlan(bob.id, drug.id, UsingUpdateDTO(qty(60.0)))
        }
    }

    // ── recordIntake ──

    @Test
    fun `recordIntake reduces both drug quantity and planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val updated = usingService.recordIntake(alice.id, drug.id, qty(10.0))
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        val ex = assertFailsWith<ResponseStatusException> {
            usingService.recordIntake(alice.id, drug.id, qty(15.0))
        }
        assertTrue(ex.reason!!.contains("exceeds planned amount"))
    }

    @Test
    fun `recordIntake throws when exceeding drug quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        // Artificially corrupt DB state to simulate race condition
        val directDrug = drugService.findByIdForUserForUpdate(drug.id, alice.id)
        directDrug.quantity = qty(2.0)
        drugRepository.saveAndFlush(directDrug)

        val ex = assertFailsWith<ResponseStatusException> {
            usingService.recordIntake(alice.id, drug.id, qty(5.0))
        }
        assertTrue(ex.reason!!.contains("Insufficient drug quantity"))
    }

    @Test
    fun `recordIntake exactly consuming planned amount deletes plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        val result = usingService.recordIntake(alice.id, drug.id, qty(10.0))
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
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

        val using = usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        val dto = usingService.toUsingDTO(using)
        assertEquals(alice.id, dto.userId)
        assertEquals(drug.id, dto.drugId)
        assertQty(30.0, dto.plannedAmount)
    }
}
