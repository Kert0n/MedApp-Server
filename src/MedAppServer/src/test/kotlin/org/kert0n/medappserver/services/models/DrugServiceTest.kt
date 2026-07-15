package org.kert0n.medappserver.services.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.controller.DrugCreateDTO
import org.kert0n.medappserver.controller.DrugUpdateDTO
import org.kert0n.medappserver.controller.UsingCreateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        val drug = dbHelper.freshDrug(kit, 10.0.toBigDecimal())
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
        val drug = dbHelper.freshDrug(kit, 10.0.toBigDecimal())
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
        dbHelper.freshDrug(kit, 10.0.toBigDecimal())
        dbHelper.freshDrug(kit, 20.0.toBigDecimal())
        dbHelper.flushAndClear()

        assertEquals(2, drugService.findAllByMedKit(kit.id).size)
    }

    @Test
    fun `findAllByUser returns drugs user has treatment plans for`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0.toBigDecimal())
        dbHelper.flushAndClear()

        assertEquals(0, drugService.findAllByUser(alice.id).size)

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, 10.0.toBigDecimal()))
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
            DrugCreateDTO(name = "Aspirin", quantity = 100.0.toBigDecimal(), quantityUnit = "mg", medKitId = kit.id),
            kit, alice.id
        )

        assertNotNull(drug.id)
        assertEquals("Aspirin", drug.name)
        org.kert0n.medappserver.testutil.assertDecimalEquals(100.0.toBigDecimal(), drug.quantity)
        assertEquals(kit.id, drug.medKit.id)
    }

    // ── update ──

    @Test
    fun `update with all nulls leaves drug unchanged`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0.toBigDecimal())
        dbHelper.flushAndClear()

        val emptyUpdate = DrugUpdateDTO(null, null, null, null, null, null, null, null)
        drugService.update(drug.id, emptyUpdate, alice.id)
        dbHelper.flushAndClear()

        org.kert0n.medappserver.testutil.assertDecimalEquals(10.0.toBigDecimal(), drugService.findById(drug.id).quantity)
    }

    @Test
    fun `update with all fields populates every property`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0.toBigDecimal())
        dbHelper.flushAndClear()

        val fullUpdate = DrugUpdateDTO(
            name = "New Name", quantity = 100.0.toBigDecimal(), quantityUnit = "ml",
            formType = "liquid", category = "cat", manufacturer = "man",
            country = "co", description = "desc"
        )
        drugService.update(drug.id, fullUpdate, alice.id)
        dbHelper.flushAndClear()

        val updated = drugService.findById(drug.id)
        assertEquals("New Name", updated.name)
        org.kert0n.medappserver.testutil.assertDecimalEquals(100.0.toBigDecimal(), updated.quantity)
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
        val drug = dbHelper.freshDrug(kit, 10.0.toBigDecimal())
        dbHelper.flushAndClear()

        drugService.update(drug.id, DrugUpdateDTO(quantity = 20.0.toBigDecimal()), alice.id)
        dbHelper.flushAndClear()

        org.kert0n.medappserver.testutil.assertDecimalEquals(20.0.toBigDecimal(), dbHelper.drugQuantity(drug.id))
    }

    @Test
    fun `update decreasing quantity triggers reduction logic`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0.toBigDecimal())
        dbHelper.flushAndClear()

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, 80.0.toBigDecimal()))
        dbHelper.flushAndClear()

        drugService.update(drug.id, DrugUpdateDTO(quantity = 40.0.toBigDecimal()), alice.id)
        dbHelper.flushAndClear()

        org.kert0n.medappserver.testutil.assertDecimalEquals(40.0.toBigDecimal(), dbHelper.drugQuantity(drug.id)!!, 0.001.toBigDecimal())
        org.kert0n.medappserver.testutil.assertDecimalEquals(40.0.toBigDecimal(), dbHelper.userPlan(alice.id, drug.id)!!, 0.001.toBigDecimal())
    }

    // ── delete ──

    @Test
    fun `delete removes drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0.toBigDecimal())
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
        val drug = dbHelper.freshDrug(kit, 100.0.toBigDecimal())
        dbHelper.flushAndClear()

        val consumed = drugService.consumeDrug(drug.id, 30.0.toBigDecimal(), alice.id)
        org.kert0n.medappserver.testutil.assertDecimalEquals(70.0.toBigDecimal(), consumed?.quantity)
    }

    @Test
    fun `consumeDrug throws when insufficient quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0.toBigDecimal())
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.consumeDrug(drug.id, 20.0.toBigDecimal(), alice.id)
        }
    }

    // ── toDrugDTO ──

    @Test
    fun `toDrugDTO includes planned quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = drugService.create(
            DrugCreateDTO(name = "Drug", quantity = 100.0.toBigDecimal(), quantityUnit = "mg", medKitId = kit.id),
            kit, alice.id
        )
        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, 25.0.toBigDecimal()))
        dbHelper.flushAndClear()

        val dto = drugService.toDrugDTO(drugService.findById(drug.id))
        org.kert0n.medappserver.testutil.assertDecimalEquals(25.0.toBigDecimal(), dto.plannedQuantity)
        org.kert0n.medappserver.testutil.assertDecimalEquals(100.0.toBigDecimal(), dto.quantity)
    }
}
