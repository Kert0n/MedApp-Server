package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toCommand
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateDTO
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MedKitLifecycleServiceTest {

    @Autowired
    private lateinit var lifecycle: MedKitLifecycleService
    @Autowired
    private lateinit var queries: MedKitQueryService
    @Autowired
    private lateinit var drugCommands: DrugCommandService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var usingRepository: UsingRepository
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var medKitRepository: MedKitRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `create invitation and join form one lifecycle`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val medKitId = lifecycle.create(alice.id)

        val key = lifecycle.createInvitation(alice.id, medKitId)
        assertEquals(medKitId, lifecycle.join(bob.id, key))
        assertNotNull(medKitService.findByIdForUser(medKitId, bob.id))
    }

    // ── createDrugInMedkit ──

    @Test
    fun `createDrugInMedkit creates drug in user medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val drug = drugCommands.create(
            alice.id,
            kit.id,
            DrugCreateDTO(name = "Aspirin", quantity = qty(100.0), quantityUnit = "mg", medKitId = kit.id).toCommand()
        )

        assertNotNull(drug.id)
        assertEquals("Aspirin", drug.name)
    }

    @Test
    fun `createDrugInMedkit fails for unauthorized user`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            drugCommands.create(
                eve.id,
                kit.id,
                DrugCreateDTO(name = "Drug", quantity = qty(10.0), quantityUnit = "mg", medKitId = kit.id).toCommand(),
            )
        }
    }

    // ── moveDrug ──

    @Test
    fun `moveDrug moves drug to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit1 = medKitService.createNew(alice.id)
        val kit2 = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit1, 50.0)
        dbHelper.flushAndClear()

        val moved = drugCommands.move(alice.id, drug.id, kit2.id)
        assertEquals(kit2.id, moved.medKit.id)
    }

    @Test
    fun `moveDrug strips access from unauthorized users`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val sourceKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(sourceKit.id, alice.id), bob.id)

        val targetKit = medKitService.createNew(alice.id) // Only Alice
        val drug = dbHelper.freshDrug(sourceKit, 50.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        treatmentPlanService.create(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        drugCommands.move(alice.id, drug.id, targetKit.id)
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

        val drug = drugCommands.create(
            alice.id, kitA.id, DrugCreateDTO("Shared Meds", qty(10.0), "pcs", kitA.id).toCommand()
        )
        val kitB = medKitService.createNew(bob.id)
        dbHelper.flushAndClear()

        assertDoesNotThrow {
            drugCommands.move(bob.id, drug.id, kitB.id)
        }

        assertEquals(kitB.id, drugRepository.findById(drug.id).get().medKit.id)
    }

    @Test
    fun `moveDrug throws when target medkit not found`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugCommands.move(alice.id, drug.id, UUID.randomUUID())
        }
    }

    // ── removeUserFromMedKit ──

    @Test
    fun `removeUserFromMedKit removes user and their usings`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.addUserToMedKit(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        lifecycle.leave(bob.id, kit.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.findByIdForUser(kit.id, alice.id))
        assertFailsWith<ResponseStatusException> {
            medKitService.findByIdForUser(kit.id, bob.id)
        }
    }

    @Test
    fun `last member leaving deletes medkit drugs and plans`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()
        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        lifecycle.leave(alice.id, kit.id)
        dbHelper.flushAndClear()

        assertNull(medKitRepository.findById(kit.id).orElse(null))
        assertNull(drugRepository.findById(drug.id).orElse(null))
        assertEquals(0, usingRepository.findAllByDrugId(drug.id).size)
    }

    // ── delete ──

    @Test
    fun `delete without transfer removes medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        lifecycle.delete(alice.id, kit.id, null)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            medKitService.findByIdForUser(kit.id, alice.id)
        }
    }

    @Test
    fun `delete with transfer migrates drugs to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kitA = medKitService.createNew(alice.id)
        val kitB = medKitService.createNew(alice.id)
        val drug = drugCommands.create(
            alice.id,
            kitA.id,
            DrugCreateDTO("Migrating Drug", qty(10.0), "pcs", kitA.id).toCommand()
        )
        dbHelper.flushAndClear()

        lifecycle.delete(alice.id, kitA.id, kitB.id)
        dbHelper.flushAndClear()

        assertNull(medKitRepository.findById(kitA.id).orElse(null))
        val survivingDrug = drugRepository.findById(drug.id).orElse(null)
        assertNotNull(survivingDrug)
        assertEquals(kitB.id, survivingDrug.medKit.id)
    }

    @Test
    fun `delete with transfer strips unauthorized usings`() {
        val alice = dbHelper.freshUser("alice")
        val charlie = dbHelper.freshUser("charlie")
        val oldKit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(oldKit.id, alice.id), charlie.id)

        val newKit = medKitService.createNew(alice.id) // Only Alice

        val drug = dbHelper.freshDrug(oldKit, 90.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        treatmentPlanService.create(charlie.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        lifecycle.delete(alice.id, oldKit.id, newKit.id)
        dbHelper.flushAndClear()

        assertNotNull(dbHelper.userPlan(alice.id, drug.id))
        assertNull(dbHelper.userPlan(charlie.id, drug.id))
    }

    @Test
    fun `delete throws NOT_FOUND for non-existent medkit`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            lifecycle.delete(alice.id, UUID.randomUUID(), null)
        }
    }

    // ── toMedKitDTO ──

    @Test
    fun `toMedKitDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        drugCommands.create(
            alice.id, kit.id,
            DrugCreateDTO(name = "Drug A", quantity = qty(50.0), quantityUnit = "mg", medKitId = kit.id).toCommand()
        )
        drugCommands.create(
            alice.id, kit.id,
            DrugCreateDTO(name = "Drug B", quantity = qty(30.0), quantityUnit = "tablets", medKitId = kit.id).toCommand()
        )
        dbHelper.flushAndClear()

        // Через medKitContent: drugsWithPlans стал приватным — это внутренний шаг, а не
        // самостоятельная операция оркестратора.
        val dto = queries.getContent(alice.id, kit.id).toDto()
        assertEquals(kit.id, dto.id)
        assertEquals(2, dto.drugs.size)
    }
}
