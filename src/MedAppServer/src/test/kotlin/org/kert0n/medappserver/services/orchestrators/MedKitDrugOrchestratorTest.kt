package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.db.store.MedKitStore
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.kert0n.medappserver.domain.DomainRuleViolated

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MedKitDrugOrchestratorTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator
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

    // ── createDrugInMedKit ──

    @Test
    fun `createDrugInMedKit creates drug in user medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val drug = medKitDrugOrchestrator.createDrugInMedKit(
            kit.id,
            DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id),
            alice.id
        )

        assertNotNull(drug.id)
        assertEquals("Aspirin", drug.name)
    }

    @Test
    fun `createDrugInMedKit fails for unauthorized user`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitDrugOrchestrator.createDrugInMedKit(
                kit.id,
                DrugCreateRequest(name = "Drug", quantity = qty(10.0), quantityUnitId = dbHelper.unit().id),
                eve.id
            )
        }
    }

    // ── moveDrug ──

    @Test
    fun `moveDrug moves drug to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit1 = medKitService.createNew(alice.id)
        val kit2 = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit1.id, 50.0)
        dbHelper.flushAndClear()

        val moved = medKitDrugOrchestrator.moveDrug(drug.id, kit2.id, alice.id)
        assertEquals(kit2.id, moved.medKitId)
    }

    @Test
    fun `moveDrug strips access from unauthorized users`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val sourceKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(sourceKit.id, alice.id), bob.id)

        val targetKit = medKitService.createNew(alice.id) // Only Alice
        val drug = dbHelper.freshDrug(sourceKit.id, 50.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(10.0))
        drugService.createPlan(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        medKitDrugOrchestrator.moveDrug(drug.id, targetKit.id, alice.id)
        dbHelper.flushAndClear()

        assertNull(dbHelper.userPlan(bob.id, drug.id))
        assertNotNull(dbHelper.userPlan(alice.id, drug.id))
    }

    @Test
    fun `moveDrug without personal treatment plan works`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kitA = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kitA.id, alice.id), bob.id)

        val drug = drugService.create(
            DrugCreateRequest("Shared Meds", qty(10.0), dbHelper.unit().id), kitA.id, alice.id
        )
        val kitB = medKitService.createNew(bob.id)
        dbHelper.flushAndClear()

        assertDoesNotThrow {
            medKitDrugOrchestrator.moveDrug(drug.id, kitB.id, bob.id)
        }

        assertEquals(kitB.id, drugService.requireById(drug.id).medKitId)
    }

    @Test
    fun `moveDrug throws when target medkit not found`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            medKitDrugOrchestrator.moveDrug(drug.id, UUID.randomUUID(), alice.id)
        }
    }

    // ── removeUserFromMedKit ──

    @Test
    fun `removeUserFromMedKit removes user and their treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.addUserToMedKit(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        drugService.createPlan(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        medKitDrugOrchestrator.leaveMedKit(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.requireAccessible(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, bob.id)
        }
    }

    // ── delete ──

    @Test
    fun `delete without transfer removes medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        medKitDrugOrchestrator.delete(kit.id, alice.id, null)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, alice.id)
        }
    }

    @Test
    fun `delete with transfer migrates drugs to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kitA = medKitService.createNew(alice.id)
        val kitB = medKitService.createNew(alice.id)
        val drug = medKitDrugOrchestrator.createDrugInMedKit(
            kitA.id, DrugCreateRequest("Migrating Drug", qty(10.0), dbHelper.unit().id), alice.id
        )
        dbHelper.flushAndClear()

        medKitDrugOrchestrator.delete(kitA.id, alice.id, kitB.id)
        dbHelper.flushAndClear()

        assertNull(medKitStore.findById(kitA.id))
        val survivingDrug = drugService.findById(drug.id)
        assertNotNull(survivingDrug)
        assertEquals(kitB.id, survivingDrug.medKitId)
    }

    @Test
    fun `delete with transfer strips unauthorized treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val charlie = dbHelper.freshUser("charlie")
        val oldKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(oldKit.id, alice.id), charlie.id)

        val newKit = medKitService.createNew(alice.id) // Only Alice

        val drug = dbHelper.freshDrug(oldKit.id, 90.0)
        dbHelper.flushAndClear()

        drugService.createPlan(alice.id, drug.id, qty(30.0))
        drugService.createPlan(charlie.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        medKitDrugOrchestrator.delete(oldKit.id, alice.id, newKit.id)
        dbHelper.flushAndClear()

        assertNotNull(dbHelper.userPlan(alice.id, drug.id))
        assertNull(dbHelper.userPlan(charlie.id, drug.id))
    }

    @Test
    fun `delete throws NOT_FOUND for non-existent medkit`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            medKitDrugOrchestrator.delete(UUID.randomUUID(), alice.id, null)
        }
    }

    // ── toMedKitDTO ──

    @Test
    fun `toMedKitDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        drugService.create(
            DrugCreateRequest(name = "Drug A", quantity = qty(50.0), quantityUnitId = dbHelper.unit().id),
            kit.id, alice.id
        )
        drugService.create(
            DrugCreateRequest(name = "Drug B", quantity = qty(30.0), quantityUnitId = dbHelper.unit().id),
            kit.id, alice.id
        )
        dbHelper.flushAndClear()

        val dto = medKitDrugOrchestrator.medKitWithDrugs(kit.id, alice.id)
        assertEquals(kit.id, dto.id)
        assertEquals(2, dto.drugs.size)
    }
}
