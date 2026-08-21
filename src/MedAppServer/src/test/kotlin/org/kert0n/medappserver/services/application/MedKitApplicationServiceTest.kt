package org.kert0n.medappserver.services.application

import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MedKitApplicationServiceTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var medKits: MedKitApplicationService
    @Autowired
    private lateinit var drugs: DrugApplicationService
    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var reservationService: ReservationService
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── createDrugInMedKit ──

    @Test
    fun `createDrugInMedKit creates drug in user medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        val drug = drugs.createInMedKit(
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
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            drugs.createInMedKit(
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
        val kit1 = medKitService.create(alice.id)
        val kit2 = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit1.id, 50.0)
        dbHelper.flushAndClear()

        val moved = drugs.moveToMedKit(drug.id, kit2.id, alice.id)
        assertEquals(kit2.id, moved.medKitId)
    }

    @Test
    fun `moveDrug strips access from unauthorized users`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val sourceKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(sourceKit.id, alice.id), alice.id), bob.id)

        val targetKit = medKitService.create(alice.id) // Only Alice
        val drug = dbHelper.freshDrug(sourceKit.id, 50.0)
        dbHelper.flushAndClear()

        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(10.0))
        reservationService.create(drugService.get(drug.id, bob.id), bob.id, qty(10.0))
        dbHelper.flushAndClear()

        drugs.moveToMedKit(drug.id, targetKit.id, alice.id)
        dbHelper.flushAndClear()

        assertNull(dbHelper.userReservation(bob.id, drug.id))
        assertNotNull(dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `moveDrug without personal treatment plan works`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kitA = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(kitA.id, alice.id), alice.id), bob.id)

        val drug = drugService.create(
            NewDrug("Shared Meds", qty(10.0), dbHelper.unit().id), medKitService.get(kitA.id, alice.id)
        )
        val kitB = medKitService.create(bob.id)
        dbHelper.flushAndClear()

        assertDoesNotThrow {
            drugs.moveToMedKit(drug.id, kitB.id, bob.id)
        }

        assertEquals(kitB.id, dbHelper.requireDrug(drug.id).medKitId)
    }

    @Test
    fun `moveDrug throws when target medkit not found`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugs.moveToMedKit(drug.id, UUID.randomUUID(), alice.id)
        }
    }

    // ── removeUserFromMedKit ──

    @Test
    fun `removeUserFromMedKit removes user and their treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        reservationService.create(drugService.get(drug.id, bob.id), bob.id, qty(10.0))
        dbHelper.flushAndClear()

        medKits.leave(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.get(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.get(kit.id, bob.id)
        }
    }

    // ── delete ──

    @Test
    fun `delete without transfer removes medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        medKits.delete(kit.id, alice.id, null)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            medKitService.get(kit.id, alice.id)
        }
    }

    @Test
    fun `delete with transfer migrates drugs to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kitA = medKitService.create(alice.id)
        val kitB = medKitService.create(alice.id)
        val drug = drugs.createInMedKit(
            kitA.id, DrugCreateRequest("Migrating Drug", qty(10.0), dbHelper.unit().id), alice.id
        )
        dbHelper.flushAndClear()

        medKits.delete(kitA.id, alice.id, kitB.id)
        dbHelper.flushAndClear()

        assertNull(dbHelper.medKit(kitA.id))
        val survivingDrug = dbHelper.drug(drug.id)
        assertNotNull(survivingDrug)
        assertEquals(kitB.id, survivingDrug.medKitId)
    }

    @Test
    fun `delete with transfer strips unauthorized treatment plans`() {
        val alice = dbHelper.freshUser("alice")
        val charlie = dbHelper.freshUser("charlie")
        val oldKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, alice.id), alice.id), charlie.id)

        val newKit = medKitService.create(alice.id) // Only Alice

        val drug = dbHelper.freshDrug(oldKit.id, 90.0)
        dbHelper.flushAndClear()

        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(30.0))
        reservationService.create(drugService.get(drug.id, charlie.id), charlie.id, qty(30.0))
        dbHelper.flushAndClear()

        medKits.delete(oldKit.id, alice.id, newKit.id)
        dbHelper.flushAndClear()

        assertNotNull(dbHelper.userReservation(alice.id, drug.id))
        assertNull(dbHelper.userReservation(charlie.id, drug.id))
    }

    @Test
    fun `delete throws NOT_FOUND for non-existent medkit`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            medKits.delete(UUID.randomUUID(), alice.id, null)
        }
    }

    // ── toMedKitDTO ──

    @Test
    fun `toMedKitDTO returns correct DTO`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        drugService.create(
            NewDrug(name = "Drug A", quantity = qty(50.0), quantityUnitId = dbHelper.unit().id),
            medKitService.get(kit.id, alice.id)
        )
        drugService.create(
            NewDrug(name = "Drug B", quantity = qty(30.0), quantityUnitId = dbHelper.unit().id),
            medKitService.get(kit.id, alice.id)
        )
        dbHelper.flushAndClear()

        val dto = medKits.read(kit.id, alice.id)
        assertEquals(kit.id, dto.id)
        assertEquals(2, dto.drugs.size)
    }
}
