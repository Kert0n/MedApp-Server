package org.kert0n.medappserver.services.aggregate

import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.InsufficientStock
import org.kert0n.medappserver.domain.InvalidQuantity
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrugServiceTest {

    @Autowired
    private lateinit var drugService: DrugService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var reservationService: ReservationService
    @Autowired
    private lateinit var drugRepository: DrugRepository
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── findById ──

    @Test
    fun `несуществующий препарат неотличим от недоступного`() {
        val alice = dbHelper.freshUser("alice")

        assertThrows<NotAMember> { drugService.get(UUID.randomUUID(), alice.id) }
    }

    // ── findByIdForUser / findByIdForUserForUpdate ──

    @Test
    fun `findByIdForUser throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugService.get(drug.id, eve.id)
        }
    }

    @Test
    fun `findByIdForUserForUpdate throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<DomainRuleViolated> {
            drugService.get(drug.id, eve.id)
        }
    }

    // ── findAllByMedKit / findAllByUser ──

    @Test
    fun `findAllByMedKit returns drugs in medkit`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.freshDrug(kit.id, 20.0)
        dbHelper.flushAndClear()

        assertEquals(2, drugService.ofMedKit(kit.id, alice.id).size)
    }

    @Test
    fun `findAllByUser returns drugs user has treatment plans for`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertEquals(0, reservationService.ofUser(alice.id).size)

        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(10.0))
        dbHelper.flushAndClear()

        assertEquals(1, reservationService.ofUser(alice.id).size)
    }

    // ── create ──

    @Test
    fun `create saves and returns drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        val drug = drugService.create(
            NewDrug(name = "Aspirin", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id),
            medKitService.get(kit.id, alice.id)
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
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        val emptyUpdate = DrugEdit(null, null, null, null, null, null, null, null)
        drugService.update(drugService.get(drug.id, alice.id), emptyUpdate)
        dbHelper.flushAndClear()

        assertQty(10.0, dbHelper.requireDrug(drug.id).quantity)
    }

    @Test
    fun `update with all fields populates every property`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        val fullUpdate = DrugEdit(
            name = "New Name", quantity = qty(100.0), category = "cat", manufacturer = "man",
            country = "co", description = "desc"
        )
        drugService.update(drugService.get(drug.id, alice.id), fullUpdate)
        dbHelper.flushAndClear()

        val updated = dbHelper.requireDrug(drug.id)
        assertEquals("New Name", updated.name)
        assertQty(100.0, updated.quantity)
        assertEquals("cat", updated.category)
        assertEquals("man", updated.manufacturer)
        assertEquals("co", updated.country)
        assertEquals("desc", updated.description)
    }

    @Test
    fun `update increasing quantity bypasses reduction`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        drugService.update(drugService.get(drug.id, alice.id), DrugEdit(quantity = qty(20.0)))
        dbHelper.flushAndClear()

        assertQty(20.0, dbHelper.drugQuantity(drug.id))
    }

    /**
     * Пересчёт учёта вниз: в пачке оказалось меньше, чем числилось. Брони не двигаются ни на
     * сколько — сколько из своей оставить, решает её владелец.
     */
    @Test
    fun `update decreasing quantity leaves reservations alone`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(kit.id, alice.id), alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(60.0))
        reservationService.create(drugService.get(drug.id, bob.id), bob.id, qty(40.0))
        dbHelper.flushAndClear()

        drugService.update(drugService.get(drug.id, alice.id), DrugEdit(quantity = qty(50.0)))
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id)!!)
        assertQty(60.0, dbHelper.userReservation(alice.id, drug.id)!!)
        assertQty(40.0, dbHelper.userReservation(bob.id, drug.id)!!)
        // Заявлено сто на пачку из пятидесяти — законное состояние, а не повод для пересчёта.
        assertQty(100.0, dbHelper.reservedOnDrug(drug.id))
    }

    @Test
    fun `update refuses a non-positive quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        assertThrows<InvalidQuantity> {
            drugService.update(drugService.get(drug.id, alice.id), DrugEdit(quantity = qty(0.0)))
        }
    }

    // ── delete ──

    @Test
    fun `delete removes drug`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        drugService.delete(drugService.get(drug.id, alice.id))
        dbHelper.flushAndClear()

        assertNull(dbHelper.drug(drug.id))
    }

    // ── consumeDrug ──

    @Test
    fun `consumeDrug reduces quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        dbHelper.flushAndClear()

        val consumed = drugService.consume(drugService.get(drug.id, alice.id), qty(30.0))
        assertQty(70.0, consumed?.quantity)
    }

    @Test
    fun `consumeDrug throws when insufficient quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertThrows<InsufficientStock> {
            drugService.consume(drugService.get(drug.id, alice.id), qty(20.0))
        }
    }

    // ── Представление ──

    @Test
    fun `представление упаковки несёт заявленное бронями`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = drugService.create(
            NewDrug(name = "Drug", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id),
            medKitService.get(kit.id, alice.id)
        )
        reservationService.create(drugService.get(drug.id, alice.id), alice.id, qty(25.0))
        dbHelper.flushAndClear()

        // Заявленное приходит извне упаковки: сама она про брони не знает.
        val dto = drugService.get(drug.id, alice.id).toDto(reservationService.onDrugs(listOf(drug.id), alice.id))
        assertQty(100.0, dto.quantity)
        assertQty(25.0, dto.reservedQuantity)
    }
}
