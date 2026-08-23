package org.kert0n.medappserver.integration.userstory

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.orchestrator.DrugDisposal
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class ComplexWorkflowStoriesTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Autowired
    private lateinit var reservationService: ReservationService



    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var disposal: DrugDisposal

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var drugs: DrugApplicationService

    @Autowired
    private lateinit var medKits: MedKitApplicationService


    /**
     * История 17: общая аптечка от первого приглашения до автоудаления.
     *
     * Здесь сходятся правила, которые поодиночке проверены своими тестами: приём не двигает
     * брони, потерявший доступ теряет и бронь — и на одиночном переезде, и на переносе целой
     * аптечки, — а опустевшая аптечка уходит сама.
     */
    @Test
    fun `Story 17 - The Roommate Saga complex interwoven workflow`() {
        // ── Фаза 1: аптечка и участники ──
        val alice = dbHelper.insert(User(id = Uuid.random(), hashedKey = "alice_${Uuid.random()}"))
        val bob = dbHelper.insert(User(id = Uuid.random(), hashedKey = "bob_${Uuid.random()}"))
        val charlie = dbHelper.insert(User(id = Uuid.random(), hashedKey = "charlie_${Uuid.random()}"))

        val homeKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(homeKit.id, alice.id), alice.id), bob.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(homeKit.id, alice.id), alice.id), charlie.id)

        val allergyMeds = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Allergy Meds", quantity = Quantity(qty(60.0), dbHelper.unit()), medKitId = homeKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )
        val painkillers = dbHelper.insert(
            Drug(
                id = Uuid.random(), name = "Painkillers", quantity = Quantity(qty(100.0), dbHelper.unit()), medKitId = homeKit.id, formType = null,
                category = null,
                manufacturer = null,
                country = null,
                description = null
            )
        )


        // ── Фаза 2: каждый бронирует свою долю ──
        // От аллергии: в пачке 60, заявлено 20 + 20 + 20 = 60.
        dbHelper.reserve(alice.id, allergyMeds.id, qty(20.0))
        dbHelper.reserve(bob.id, allergyMeds.id, qty(20.0))
        dbHelper.reserve(charlie.id, allergyMeds.id, qty(20.0))

        // Обезболивающее: в пачке 100, заявлено 30 + 30.
        dbHelper.reserve(bob.id, painkillers.id, qty(30.0))
        dbHelper.reserve(charlie.id, painkillers.id, qty(30.0))


        // ── Фаза 3: приём ──
        // Боб съедает 30: в пачке остаётся 30, заявлено по-прежнему 60.
        drugService.consume(drugService.get(allergyMeds.id, bob.id), qty(30.0), dbHelper.drugVersion(allergyMeds.id))


        val updatedAllergyMeds = dbHelper.requireDrug(allergyMeds.id)
        assertQty(30.0, updatedAllergyMeds.quantity, "Stock should be 30")

        // Бронь не двигается: пачка уменьшилась, а сколько из своей брони оставить — дело Алисы.
        val aliceAllergyReservation = dbHelper.userReservation(alice.id, allergyMeds.id)!!
        assertQty(20.0, aliceAllergyReservation, "бронь Алисы осталась прежней")

        // ── Фаза 4: переезд одной пачки ──
        val travelKit = medKitService.create(alice.id)


        drugs.moveToMedKit(painkillers.id, travelKit.id, dbHelper.drugVersion(painkillers.id), alice.id)


        // Боб и Чарли дорожную аптечку не видят — их брони уходят вместе с пачкой.
        assertNull(dbHelper.userReservation(bob.id, painkillers.id), "Bob's reservation must be deleted")
        assertNull(dbHelper.userReservation(charlie.id, painkillers.id), "Charlie's reservation must be deleted")

        val movedPainkillers = dbHelper.requireDrug(painkillers.id)
        assertEquals(travelKit.id, movedPainkillers.medKitId, "Drug successfully moved")

        // ── Фаза 5: удаление аптечки с переносом содержимого ──
        val duoKit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(duoKit.id, alice.id), alice.id), bob.id)


        medKits.delete(homeKit.id, dbHelper.medKitVersion(homeKit.id), alice.id, duoKit.id)


        assertNull(dbHelper.medKit(homeKit.id), "Home kit must be completely deleted")

        val migratedAllergyMeds = dbHelper.drug(allergyMeds.id)
        assertNotNull(migratedAllergyMeds, "Allergy meds must survive the migration")
        assertEquals(duoKit.id, migratedAllergyMeds.medKitId, "Allergy meds are in Duo Kit")

        // Чарли в новую аптечку не входит — его бронь снята.
        assertNull(
            dbHelper.userReservation(charlie.id, allergyMeds.id),
            "Charlie's last reservation must be deleted"
        )

        // Брони Алисы и Боба переехали как были
        val finalAlicePlan = dbHelper.userReservation(alice.id, allergyMeds.id)!!
        assertQty(20.0, finalAlicePlan, "бронь Алисы переехала неизменной")

        // ── Фаза 6: последний участник выходит ──
        medKits.leave(duoKit.id, dbHelper.medKitVersion(duoKit.id), bob.id)


        val duoKitCheck1 = dbHelper.medKit(duoKit.id)!!
        assertEquals(1, duoKitCheck1.members.size, "Only Alice remains")

        // Выход последнего уносит аптечку. Через `medKitService` напрямую: оркестратор стал бы
        // убирать брони, которых вместе с аптечкой уже нет.
        medKitService.leave(medKitService.get(duoKitCheck1.id, alice.id), alice.id, duoKitCheck1.version)


        assertNull(dbHelper.medKit(duoKit.id), "Duo kit must auto-delete when last user leaves")
        assertNull(
            dbHelper.drug(allergyMeds.id),
            "Cascade should kill the drugs inside the abandoned kit"
        )

        println("✅ Story 17 passed: The Roommate Saga completed")
    }

    @Test
    fun `Story 18 - Full Lifecycle Alterations, Movement, and Deletion`() {
                val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")

        val sourceKit = medKitService.create(alice.id)
        val targetKit = medKitService.create(alice.id) // Сюда доступ есть только у Алисы
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(sourceKit.id, alice.id), alice.id), bob.id)

        val createDrugDto = DrugCreateRequest(
            name = "LifePill", quantity = qty(100.0), quantityUnitId = dbHelper.unit().id
            )
        val drug = drugs.createInMedKit(sourceKit.id, createDrugDto, alice.id).drug
        dbHelper.flushAndClear()

        // Алиса и Боб заявляют по 40: 80 из 100.
        dbHelper.reserve(alice.id, drug.id, qty(40.0))
        dbHelper.reserve(bob.id, drug.id, qty(40.0))
        dbHelper.flushAndClear()

        // ── Фаза 1: правка брони ──
        // Боб поднимает свою с 40 до 60. С остатком пачки это не сверяется: решение его.
        reservationService.changeTo(reservationService.get(bob.id, drug.id), qty(60.0), dbHelper.reservationsVersion(drug.id, bob.id))
        dbHelper.flushAndClear()

        assertQty(60.0, dbHelper.userReservation(bob.id, drug.id)!!, "Bob's reservation is 60")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "Alice's is unchanged at 40")

        // ── Фаза 2: половина пачки потеряна ──
        // Алиса разлила половину. Брони не двигаются: вместе они теперь превышают содержимое
        // пачки, и это законное состояние — отвечают за него их владельцы.
        disposal.consume(drugService.get(drug.id, alice.id), qty(50.0), dbHelper.drugVersion(drug.id))
        dbHelper.flushAndClear()

        assertQty(50.0, dbHelper.drugQuantity(drug.id)!!, "в пачке осталось 50")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "бронь Алисы не тронута")
        assertQty(60.0, dbHelper.userReservation(bob.id, drug.id)!!, "бронь Боба не тронута")
        assertQty(100.0, dbHelper.reservedOnDrug(drug.id), "заявлено больше, чем в пачке")

        // ── Фаза 3: переезд пачки туда, где нет Боба ──
        drugs.moveToMedKit(drug.id, targetKit.id, dbHelper.drugVersion(drug.id), alice.id)
        dbHelper.flushAndClear()

        val movedDrug = dbHelper.requireDrug(drug.id)
        assertEquals(targetKit.id, movedDrug.medKitId, "Drug successfully moved to targetKit")

        // Боб потерял доступ — бронь снята вместе с ним.
        assertNull(dbHelper.userReservation(bob.id, drug.id), "Bob's reservation MUST be stripped")
        assertQty(40.0, dbHelper.userReservation(alice.id, drug.id)!!, "бронь Алисы не тронута")

        // ── Фаза 4: уничтожение пачки ──
        disposal.destroy(drugService.get(drug.id, alice.id), dbHelper.drugVersion(drug.id))
        dbHelper.flushAndClear()

        assertNull(dbHelper.drugQuantity(drug.id), "Drug record completely purged")
        assertNull(dbHelper.userReservation(alice.id, drug.id), "Alice's reservation purged with the pack")

        println("✅ Story 18 passed: edits, access stripping on move, and total deletion")
    }

    private fun createTestUser(name: String): User =
        dbHelper.insert(User(id = Uuid.random(), hashedKey = name))

    @Test
    fun `Story 19 - Roommate can move drug even without a reservation of their own`() {
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")

        val kitA = medKitService.create(alice.id)
        val shareKey = medKitService.invite(medKitService.get(kitA.id, alice.id), alice.id)
        medKitService.joinByInvitation(shareKey, bob.id)

        val drug = drugService.create(NewDrug("Shared Meds", qty(10.0), dbHelper.unit().id), medKitService.get(kitA.id, alice.id))
        dbHelper.flushAndClear()

        val kitB = medKitService.create(bob.id)

        // Переезжает Боб, и своей брони на пачку у него нет: право даёт членство, а не бронь.
        assertDoesNotThrow {
            drugs.moveToMedKit(drug.id, kitB.id, dbHelper.drugVersion(drug.id), bob.id)
        }

        val updatedDrug = dbHelper.requireDrug(drug.id)
        assertEquals(kitB.id, updatedDrug.medKitId)
    }

    @Test
    fun `Verify movement strips the reservations of those who lost access`() {
        val alice = createTestUser("alice")
        val bob = createTestUser("bob")
        val kitA = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(kitA.id, alice.id), alice.id), bob.id)

        val drug = drugService.create(NewDrug("Audit Meds", qty(10.0), dbHelper.unit().id), medKitService.get(kitA.id, alice.id))
        dbHelper.flushAndClear()

        dbHelper.reserve(alice.id, drug.id, qty(5.0))
        dbHelper.reserve(bob.id, drug.id, qty(2.0))

        // Личная аптечка Алисы: Боба в ней нет.
        val kitB = medKitService.create(alice.id)
        drugs.moveToMedKit(drug.id, kitB.id, dbHelper.drugVersion(drug.id), alice.id)
        val aliceReservation = dbHelper.userReservation(alice.id, drug.id)
        val bobReservation = dbHelper.userReservation(bob.id, drug.id)
        assertNotNull(aliceReservation, "Alice keeps her reservation")
        assertNull(bobReservation, "Bob's is deleted because he lost access to the pack")
    }

    @Test
    fun `Verify drug migration during MedKit deletion`() {
        val alice = createTestUser("alice")
        val kitA = medKitService.create(alice.id)
        val kitB = medKitService.create(alice.id)
        val drug =
            drugs.createInMedKit(kitA.id, DrugCreateRequest("Migrating Meds", qty(10.0), dbHelper.unit().id), alice.id)

        medKits.delete(kitA.id, dbHelper.medKitVersion(kitA.id), alice.id, kitB.id)
        val survivingDrug = dbHelper.drug(drug.drug.id)

        assertNotNull(survivingDrug, "Drug should not have been deleted")
        assertEquals(kitB.id, survivingDrug.medKitId, "Drug should be re-parented to Kit B")
    }
}
