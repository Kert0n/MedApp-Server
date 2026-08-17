package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.toDto
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.IntakeExceedsPlan
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.error.QuantityNotIncreased
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.kert0n.medappserver.domain.error.DomainRuleViolated

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrugServiceTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── findById ──

    @Test
    fun `findById throws NOT_FOUND for non-existent drug`() {
        assertThrows<DomainRuleViolated> {
            drugService.requireById(UUID.randomUUID())
        }
    }

    // ── findByIdForUser / findByIdForUserForUpdate ──

    @Test
    fun `findByIdForUser throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugService.require(drug.id, eve.id)
        }
    }

    @Test
    fun `findByIdForUserForUpdate throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugService.require(drug.id, eve.id)
        }
    }

    // ── findAllByMedKit / findAllByUser ──

    @Test
    fun `findAllByMedKit returns drugs in medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.freshDrug(kit.id, 20.0)
        dbHelper.flushAndClear()

        assertEquals(2, drugService.ofMedKit(kit.id).size)
    }

    @Test
    fun `findAllByUser returns drugs user has treatment plans for`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertEquals(0, treatmentPlanService.plansOf(alice.id).size)

        drugService.createPlan(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, treatmentPlanService.plansOf(alice.id).size)
    }

    // ── create ──

    @Test
    fun `create saves and returns drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val drug = drugService.create(
            DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnit = "mg"),
            kit.id, alice.id
        )

        assertNotNull(drug.id)
        assertEquals("Aspirin", drug.name)
        assertQty(100.0, drug.quantity)
        assertEquals(kit.id, drug.medKitId)
    }

    // ── update ──

    @Test
    fun `update with all nulls leaves drug unchanged`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        val emptyUpdate = DrugPatchRequest(null, null, null, null, null, null, null, null)
        drugService.update(drug.id, emptyUpdate, alice.id)
        dbHelper.flushAndClear()

        assertQty(10.0, drugService.requireById(drug.id).quantity)
    }

    @Test
    fun `update with all fields populates every property`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        val fullUpdate = DrugPatchRequest(
            name = "New Name", quantity = qty(100.0), quantityUnit = "ml",
            formType = "liquid", category = "cat", manufacturer = "man",
            country = "co", description = "desc"
        )
        drugService.update(drug.id, fullUpdate, alice.id)
        dbHelper.flushAndClear()

        val updated = drugService.requireById(drug.id)
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
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        drugService.update(drug.id, DrugPatchRequest(quantity = qty(20.0)), alice.id)
        dbHelper.flushAndClear()

        assertQty(20.0, dbHelper.drugQuantity(drug.id))
    }

    /**
     * Уменьшение остатка через PATCH отвергается: расход выражается списанием, и только оно
     * говорит, сколько именно ушло. Присвоенное меньшее число потеряло бы чужие списания,
     * случившиеся между чтением и записью.
     */
    @Test
    fun `update refuses to decrease quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertThrows<QuantityNotIncreased> {
            drugService.update(drug.id, DrugPatchRequest(quantity = qty(40.0)), alice.id)
        }
        dbHelper.flushAndClear()

        assertQty(100.0, dbHelper.drugQuantity(drug.id)!!)
    }

    @Test
    fun `update refuses to repeat the current quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertThrows<QuantityNotIncreased> {
            drugService.update(drug.id, DrugPatchRequest(quantity = qty(100.0)), alice.id)
        }
    }

    // ── delete ──

    @Test
    fun `delete removes drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        drugService.delete(drug.id, alice.id)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugService.requireById(drug.id)
        }
    }

    // ── consumeDrug ──

    @Test
    fun `consumeDrug reduces quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        val consumed = drugService.consume(drug.id, qty(30.0), alice.id)
        assertQty(70.0, consumed?.quantity)
    }

    @Test
    fun `consumeDrug throws when insufficient quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<InsufficientStock> {
            drugService.consume(drug.id, qty(20.0), alice.id)
        }
    }

    // ── toDrugDTO ──

    @Test
    fun `toDrugDTO includes planned quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = drugService.create(
            DrugCreateRequest(name = "Drug", quantity = qty(100.0), quantityUnit = "mg"),
            kit.id, alice.id
        )
        drugService.createPlan(alice.id, drug.id, qty(25.0))
        dbHelper.flushAndClear()

        // DTO собирается только из формы чтения: сумма планов приходит из запроса, а не из
        // поля сущности, которое после изменения планов в той же транзакции устаревало.
        val dto = drugService.require(drug.id, alice.id).toDto()
        assertQty(25.0, dto.plannedQuantity)
        assertQty(100.0, dto.quantity)
        assertQty(75.0, dto.availableQuantity)
    }

    // ── Планы лечения: часть того же агрегата ────────────────────────────────────

    @Test
    fun `createPlan reserves the amount for the user`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        val plan = drugService.createPlan(alice.id, drug.id, qty(30.0))

        assertQty(30.0, plan.plannedAmount)
        assertEquals(alice.id, plan.userId)
        assertQty(30.0, dbHelper.userPlan(alice.id, drug.id))
    }

    @Test
    fun `createPlan refuses a second plan of the same user`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        assertThrows<TreatmentPlanAlreadyExists> {
            drugService.createPlan(alice.id, drug.id, qty(20.0))
        }
    }

    @Test
    fun `createPlan refuses to reserve more than the stock`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        assertThrows<PlannedAmountExceedsStock> {
            drugService.createPlan(alice.id, drug.id, qty(100.0))
        }
    }

    @Test
    fun `changePlan updates the planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        assertQty(50.0, drugService.changePlan(alice.id, drug.id, qty(50.0)).plannedAmount)
    }

    @Test
    fun `changePlan counts other participants but not itself`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(50.0))
        drugService.createPlan(bob.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        // Своё прежнее значение Бобу не мешает: 100 - 50 Алисы = 50 доступно ему.
        assertQty(50.0, drugService.changePlan(bob.id, drug.id, qty(50.0)).plannedAmount)
        dbHelper.flushAndClear()

        assertThrows<PlannedAmountExceedsStock> {
            drugService.changePlan(bob.id, drug.id, qty(60.0))
        }
    }

    @Test
    fun `cancelPlan removes the plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        drugService.cancelPlan(alice.id, drug.id)
        dbHelper.flushAndClear()

        assertNull(dbHelper.userPlan(alice.id, drug.id))
    }

    // ── Приём ────────────────────────────────────────────────────────────────────

    @Test
    fun `recordIntake reduces both the stock and the plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val remaining = drugService.recordIntake(alice.id, drug.id, qty(10.0))

        assertNotNull(remaining)
        assertQty(20.0, remaining.plannedAmount)
        assertQty(90.0, drugService.requireById(drug.id).quantity)
    }

    @Test
    fun `recordIntake refuses more than the plan holds`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertThrows<IntakeExceedsPlan> {
            drugService.recordIntake(alice.id, drug.id, qty(15.0))
        }
    }

    @Test
    fun `recordIntake exhausting the plan removes it`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 20.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val remaining = drugService.recordIntake(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertNull(remaining)
        assertNull(dbHelper.userPlan(alice.id, drug.id))
        assertQty(10.0, dbHelper.drugQuantity(drug.id)!!)
    }
}
