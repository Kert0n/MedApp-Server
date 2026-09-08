package org.kert0n.medappserver.services.application

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.transaction.TestTransaction
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

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
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    // ── createInMedKit ──

    @Test
    fun `createInMedKit creates drug in user medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        val drug = drugs.createInMedKit(
            kit.id,
            DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id),
            alice.id
        )

        assertNotNull(drug.drug.id)
        assertEquals("Aspirin", drug.drug.name)
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

    // ── moveToMedKit ──

    @Test
    fun `moveToMedKit moves drug to target medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit1 = medKitService.create(alice.id)
        val kit2 = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit1.id, 50.0)
        dbHelper.flushAndClear()

        val moved = drugs.moveToMedKit(drug.id, kit2.id, dbHelper.drugVersion(drug.id), alice.id).drug
        assertEquals(kit2.id, moved.medKitId)
    }

    @Test
    fun `moveDrug strips access from unauthorized users`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val sourceKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(sourceKit.id, alice.id), alice.id), bob.id)

        val targetKit = medKitService.create(alice.id) // Только Алиса
        val drug = dbHelper.freshDrug(sourceKit.id, 50.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(10.0))
        dbHelper.reserve(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        drugs.moveToMedKit(drug.id, targetKit.id, dbHelper.drugVersion(drug.id), alice.id)
        dbHelper.flushAndClear()

        assertNull(dbHelper.userReservation(bob.id, drug.id))
        assertNotNull(dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `moveToMedKit works without a reservation of one's own`() {
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
            drugs.moveToMedKit(drug.id, kitB.id, dbHelper.drugVersion(drug.id), bob.id)
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
            drugs.moveToMedKit(drug.id, Uuid.random(), dbHelper.drugVersion(drug.id), alice.id)
        }
    }

    // ── leave ──

    @Test
    fun `leave removes user and their reservations`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(bob.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        medKits.leave(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.get(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.get(kit.id, bob.id)
        }
    }

    @Test
    fun `leave recalculates only snapshots touched by departing member`() {
        val alice = dbHelper.freshUser("leave-snapshot-a")
        val bob = dbHelper.freshUser("leave-snapshot-b")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val touched = dbHelper.freshDrug(kit.id, 100.0)
        val untouched = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.reserve(alice.id, touched.id, qty(7.0))
        dbHelper.reserve(bob.id, touched.id, qty(5.0))
        dbHelper.reserve(alice.id, untouched.id, qty(3.0))

        val touchedVersion = dbHelper.storedReservationsVersion(touched.id)
        val untouchedVersion = dbHelper.storedReservationsVersion(untouched.id)

        medKits.leave(kit.id, bob.id)

        assertNull(dbHelper.userReservation(bob.id, touched.id))
        assertEquals(0, dbHelper.storedReservationsTotal(touched.id).compareTo(qty(7.0)))
        assertEquals(touchedVersion + 1, dbHelper.storedReservationsVersion(touched.id))
        assertEquals(untouchedVersion, dbHelper.storedReservationsVersion(untouched.id))
        assertEquals(
            0,
            dbHelper.storedReservationsTotal(touched.id).compareTo(dbHelper.reservedOnDrug(touched.id)),
            "сохранённая сумма обязана совпадать со строками после выхода"
        )
    }

    @Test
    fun `reservation snapshot token becomes stale after another member leaves`() {
        val alice = dbHelper.freshUser("leave-token-a")
        val bob = dbHelper.freshUser("leave-token-b")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.reserve(alice.id, drug.id, qty(7.0))
        dbHelper.reserve(bob.id, drug.id, qty(5.0))
        val stale = dbHelper.storedReservationsVersion(drug.id)

        medKits.leave(kit.id, bob.id)

        assertThrows<StaleVersion> {
            reservationService.changeTo(alice.id, drug.id, qty(8.0), stale)
        }
    }

    @Test
    fun `last member leaves with reservations and removes whole medkit`() {
        val alice = dbHelper.freshUser("leave-last")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.reserve(alice.id, drug.id, qty(5.0))

        medKits.leave(kit.id, alice.id)

        assertNull(dbHelper.medKit(kit.id))
        assertNull(dbHelper.drug(drug.id))
        assertNull(dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `late failure rolls back reservations snapshot and membership together`() {
        val alice = dbHelper.freshUser("leave-rollback-a")
        val bob = dbHelper.freshUser("leave-rollback-b")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.reserve(bob.id, drug.id, qty(5.0))
        val total = dbHelper.storedReservationsTotal(drug.id)
        val version = dbHelper.storedReservationsVersion(drug.id)

        // Подготовка должна стать видна новой транзакции, которую мы намеренно откатим.
        TestTransaction.flagForCommit()
        TestTransaction.end()

        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactionManager).execute {
                medKits.leave(kit.id, bob.id)
                error("поздний отказ после всех изменений")
            }
        }

        assertTrue(dbHelper.isMember(kit.id, bob.id))
        assertNotNull(dbHelper.userReservation(bob.id, drug.id))
        assertEquals(total, dbHelper.storedReservationsTotal(drug.id))
        assertEquals(version, dbHelper.storedReservationsVersion(drug.id))

        // Возвращаем тестовую транзакцию, чтобы стандартный listener завершил метод штатно.
        TestTransaction.start()
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
        val survivingDrug = dbHelper.drug(drug.drug.id)
        assertNotNull(survivingDrug)
        assertEquals(kitB.id, survivingDrug.medKitId)
    }

    @Test
    fun `delete with transfer strips reservations of those left out`() {
        val alice = dbHelper.freshUser("alice")
        val charlie = dbHelper.freshUser("charlie")
        val oldKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(oldKit.id, alice.id), alice.id), charlie.id)

        val newKit = medKitService.create(alice.id) // Только Алиса

        val drug = dbHelper.freshDrug(oldKit.id, 90.0)
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(30.0))
        dbHelper.reserve(charlie.id, drug.id, qty(30.0))
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
            medKits.delete(Uuid.random(), alice.id, null)
        }
    }

    // ── read ──

    @Test
    fun `read returns the kit with its drugs`() {
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
