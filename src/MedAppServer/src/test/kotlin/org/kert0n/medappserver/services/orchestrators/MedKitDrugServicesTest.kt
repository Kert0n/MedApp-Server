package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.controller.DrugCreateDTO
import org.kert0n.medappserver.controller.UsingCreateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
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
class MedKitDrugServicesTest {

    @Autowired
    private lateinit var medKitDrugServices: MedKitDrugServices
    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var usingService: UsingService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var medKitRepository: MedKitRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── createDrugInMedkit ──

    @Test
    fun `createDrugInMedkit creates drug in user medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val drug = medKitDrugServices.createDrugInMedkit(
            DrugCreateDTO(name = "Aspirin", quantity = qty(100.0), quantityUnit = "mg", medKitId = kit.id),
            alice.id
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
            medKitDrugServices.createDrugInMedkit(
                DrugCreateDTO(name = "Drug", quantity = qty(10.0), quantityUnit = "mg", medKitId = kit.id),
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
        val drug = dbHelper.freshDrug(kit1, 50.0)
        dbHelper.flushAndClear()

        val moved = medKitDrugServices.moveDrug(drug.id, kit2.id, alice.id)
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(10.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        medKitDrugServices.moveDrug(drug.id, targetKit.id, alice.id)
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
            DrugCreateDTO("Shared Meds", qty(10.0), "pcs", kitA.id), kitA, alice.id
        )
        val kitB = medKitService.createNew(bob.id)
        dbHelper.flushAndClear()

        assertDoesNotThrow {
            medKitDrugServices.moveDrug(drug.id, kitB.id, bob.id)
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
            medKitDrugServices.moveDrug(drug.id, UUID.randomUUID(), alice.id)
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

        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(10.0)))
        dbHelper.flushAndClear()

        medKitDrugServices.removeUserFromMedKit(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.findByIdForUser(kit.id, alice.id))
        assertFailsWith<ResponseStatusException> {
            medKitService.findByIdForUser(kit.id, bob.id)
        }
    }

    // ── delete ──

    @Test
    fun `delete without transfer removes medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        medKitDrugServices.delete(kit.id, alice.id, null)
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
        val drug = medKitDrugServices.createDrugInMedkit(
            DrugCreateDTO("Migrating Drug", qty(10.0), "pcs", kitA.id), alice.id
        )
        dbHelper.flushAndClear()

        medKitDrugServices.delete(kitA.id, alice.id, kitB.id)
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

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(30.0)))
        usingService.createTreatmentPlan(charlie.id, UsingCreateDTO(drug.id, qty(30.0)))
        dbHelper.flushAndClear()

        medKitDrugServices.delete(oldKit.id, alice.id, newKit.id)
        dbHelper.flushAndClear()

        assertNotNull(dbHelper.userPlan(alice.id, drug.id))
        assertNull(dbHelper.userPlan(charlie.id, drug.id))
    }

    @Test
    fun `delete throws NOT_FOUND for non-existent medkit`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            medKitDrugServices.delete(UUID.randomUUID(), alice.id, null)
        }
    }

    // ── toMedKitDTO ──

    @Test
    fun `toMedKitDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        drugService.create(
            DrugCreateDTO(name = "Drug A", quantity = qty(50.0), quantityUnit = "mg", medKitId = kit.id),
            kit, alice.id
        )
        drugService.create(
            DrugCreateDTO(name = "Drug B", quantity = qty(30.0), quantityUnit = "tablets", medKitId = kit.id),
            kit, alice.id
        )
        dbHelper.flushAndClear()

        val dto = medKitDrugServices.toMedKitDTO(kit)
        assertEquals(kit.id, dto.id)
        assertEquals(2, dto.drugs.size)
    }
}
