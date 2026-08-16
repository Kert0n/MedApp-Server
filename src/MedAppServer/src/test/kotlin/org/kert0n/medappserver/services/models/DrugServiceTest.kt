package org.kert0n.medappserver.services.models

import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.controller.DrugCreateDTO
import org.kert0n.medappserver.controller.DrugUpdateDTO
import org.kert0n.medappserver.controller.UsingCreateDTO
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
class DrugServiceTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var usingService: UsingService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── findById ──

    @Test
    fun `findById throws NOT_FOUND for non-existent drug`() {
        assertThrows<ResponseStatusException> {
            drugService.findById(UUID.randomUUID())
        }
    }

    // ── findByIdForUser / findByIdForUserForUpdate ──

    @Test
    fun `findByIdForUser throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.findByIdForUser(drug.id, eve.id)
        }
    }

    @Test
    fun `findByIdForUserForUpdate throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.findByIdForUserForUpdate(drug.id, eve.id)
        }
    }

    // ── findAllByMedKit / findAllByUser ──

    @Test
    fun `findAllByMedKit returns drugs in medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.freshDrug(kit, 10.0)
        dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        assertEquals(2, drugService.findAllByMedKit(kit.id).size)
    }

    @Test
    fun `findAllByUser returns drugs user has treatment plans for`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        assertEquals(0, drugService.findAllByUser(alice.id).size)

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        assertEquals(1, drugService.findAllByUser(alice.id).size)
    }

    // ── create ──

    @Test
    fun `create saves and returns drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val drug = drugService.create(
            DrugCreateDTO(name = "Aspirin", quantity = qty(100.0), quantityUnit = "mg", medKitId = kit.id),
            kit, alice.id
        )

        assertNotNull(drug.id)
        assertEquals("Aspirin", drug.name)
        assertQty(100.0, drug.quantity)
        assertEquals(kit.id, drug.medKit.id)
    }

    // ── update ──

    @Test
    fun `update with all nulls leaves drug unchanged`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        val emptyUpdate = DrugUpdateDTO(null, null, null, null, null, null, null, null)
        drugService.update(drug.id, emptyUpdate, alice.id)
        dbHelper.flushAndClear()

        assertQty(10.0, drugService.findById(drug.id).quantity)
    }

    @Test
    fun `update with all fields populates every property`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        val fullUpdate = DrugUpdateDTO(
            name = "New Name", quantity = qty(100.0), quantityUnit = "ml",
            formType = "liquid", category = "cat", manufacturer = "man",
            country = "co", description = "desc"
        )
        drugService.update(drug.id, fullUpdate, alice.id)
        dbHelper.flushAndClear()

        val updated = drugService.findById(drug.id)
        assertEquals("New Name", updated.name)
        assertQty(100.0, updated.quantity)
        assertEquals("ml", updated.quantityUnit)
        assertEquals("liquid", updated.formType)
        assertEquals("cat", updated.category)
        assertEquals("man", updated.manufacturer)
        assertEquals("co", updated.country)
        assertEquals("desc", updated.description)
    }

    @Test
    fun `update increasing quantity bypasses reduction`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        drugService.update(drug.id, DrugUpdateDTO(quantity = qty(20.0)), alice.id)
        dbHelper.flushAndClear()

        assertQty(20.0, dbHelper.drugQuantity(drug.id))
    }

    @Test
    fun `update decreasing quantity triggers reduction logic`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(80.0)))
        dbHelper.flushAndClear()

        drugService.update(drug.id, DrugUpdateDTO(quantity = qty(40.0)), alice.id)
        dbHelper.flushAndClear()

        assertQty(40.0, dbHelper.drugQuantity(drug.id)!!)
        assertQty(40.0, dbHelper.userPlan(alice.id, drug.id)!!)
    }

    // ── delete ──

    @Test
    fun `delete removes drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        drugService.delete(drug.id, alice.id)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.findById(drug.id)
        }
    }

    // ── consumeDrug ──

    @Test
    fun `consumeDrug reduces quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val consumed = drugService.consumeDrug(drug.id, qty(30.0), alice.id)
        assertQty(70.0, consumed?.quantity)
    }

    @Test
    fun `consumeDrug throws when insufficient quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.consumeDrug(drug.id, qty(20.0), alice.id)
        }
    }

    // ── toDrugDTO ──

    @Test
    fun `toDrugDTO includes planned quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = drugService.create(
            DrugCreateDTO(name = "Drug", quantity = qty(100.0), quantityUnit = "mg", medKitId = kit.id),
            kit, alice.id
        )
        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(25.0)))
        dbHelper.flushAndClear()

        val dto = drugService.toDrugDTO(drugService.findById(drug.id))
        assertQty(25.0, dto.plannedQuantity)
        assertQty(100.0, dto.quantity)
    }
}
