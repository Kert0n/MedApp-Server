package org.kert0n.medappserver.integration

import org.kert0n.medappserver.services.models.ReservationService
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Граница хранилищ.
 *
 * Раньше на этом месте лежали тесты репозиториев: они звали методы Spring Data и смотрели на
 * сущности. Теперь у приложения другая граница — хранилище, отдающее доменные значения, — и
 * проверять надо её: сервисы ниже уровня хранилища не заглядывают, а значит и тест не должен.
 */
@PostgresIntegrationTest
@Transactional
class StoreIntegrationTest {

    @Autowired private lateinit var drugs: DrugStore
    @Autowired private lateinit var reservations: ReservationStore
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var medKits: MedKitStore
    @Autowired private lateinit var users: UserStore
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    // ── Препараты ────────────────────────────────────────────────────────────────

    @Test
    fun `упаковки аптечки читаются без броней — упаковка о них не знает`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val first = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.freshDrug(kit.id, 20.0)
        reservationService.create(alice.id, first.id, qty(4.0))
        dbHelper.flushAndClear()

        val loaded = drugs.findAllInMedKit(kit.id)

        assertEquals(2, loaded.size)
        assertQty(10.0, loaded.single { it.id == first.id }.quantity)
        // Заявленное лежит в своём агрегате и читается отдельно.
        assertQty(4.0, dbHelper.reservedOnDrug(first.id))
    }

    @Test
    fun `пустая аптечка отдаёт пустой список`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertTrue(drugs.findAllInMedKit(kit.id).isEmpty())
    }

    @Test
    fun `препарат читается участником и не читается посторонним`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertEquals(drug.id, drugs.findAccessible(drug.id, alice.id)?.id)
        assertNull(drugs.findAccessible(drug.id, eve.id), "чужая аптечка не читается")
    }

    @Test
    fun `снимок собирает препараты всех аптечек участника`() {
        val alice = dbHelper.freshUser("alice")
        val outsider = dbHelper.freshUser("outsider")
        val first = medKitService.create(alice.id)
        val second = medKitService.create(alice.id)
        val foreign = medKitService.create(outsider.id)
        dbHelper.freshDrug(first.id, 1.0)
        dbHelper.freshDrug(second.id, 2.0)
        dbHelper.freshDrug(foreign.id, 3.0)
        dbHelper.flushAndClear()

        val accessible = drugs.findAllAccessibleTo(alice.id)

        assertEquals(2, accessible.size)
        assertTrue(accessible.none { it.medKitId == foreign.id })
    }

    @Test
    fun `блокирующая загрузка отдаёт то же состояние, что и обычная`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 30.0)
        reservationService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val locked = drugs.lockAccessible(drug.id, alice.id)!!

        assertQty(30.0, locked.quantity)
        assertQty(10.0, dbHelper.reservedOnDrug(locked.id))
    }

    // ── Планы ────────────────────────────────────────────────────────────────────

    @Test
    fun `планы участника собираются по всем препаратам`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val first = dbHelper.freshDrug(kit.id, 50.0)
        val second = dbHelper.freshDrug(kit.id, 50.0)
        reservationService.create(alice.id, first.id, qty(5.0))
        reservationService.create(alice.id, second.id, qty(7.0))
        dbHelper.flushAndClear()

        val mine = reservations.findAllOfUser(alice.id)

        assertEquals(2, mine.size)
        assertQty(5.0, mine.single { it.drugId == first.id }.amount)
    }

    @Test
    fun `чужая бронь не читается по паре человек-упаковка`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        reservationService.create(alice.id, drug.id, qty(5.0))
        dbHelper.flushAndClear()

        assertQty(5.0, reservations.find(alice.id, drug.id)?.amount)
        assertNull(reservations.find(bob.id, drug.id), "у Боба брони нет")
    }

    @Test
    fun `заявленное на упаковку без броней равно нулю`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        assertQty(0.0, dbHelper.reservedOnDrug(drug.id))
    }

    // ── Аптечки и участники ──────────────────────────────────────────────────────

    @Test
    fun `аптечка заводится вместе с первым участником`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        val loaded = medKits.findById(kit.id)!!

        assertEquals(setOf(alice.id), loaded.members)
    }

    @Test
    fun `аптечки участника перечисляются, чужие в список не попадают`() {
        val alice = dbHelper.freshUser("alice")
        val outsider = dbHelper.freshUser("outsider")
        val mine = medKitService.create(alice.id)
        medKitService.create(outsider.id)
        dbHelper.flushAndClear()

        assertEquals(listOf(mine.id), medKits.findAllOfUser(alice.id).map { it.id })
    }

    @Test
    fun `счётчики аптечки считаются базой`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        dbHelper.freshDrug(kit.id, 1.0)
        dbHelper.freshDrug(kit.id, 2.0)
        dbHelper.freshDrug(kit.id, 3.0)
        dbHelper.flushAndClear()

        // Аптечка приходит агрегатом: участники в ней самой, а пачки считает вызывающий по
        // тому набору, который всё равно читал. Отдельного типа под счётчики больше нет.
        val mine = medKits.findAllOfUser(alice.id).single()

        assertEquals(2, mine.members.size)
        assertEquals(3, drugs.findAllInMedKit(kit.id).size)
    }

    @Test
    fun `удаление аптечки уносит препараты и членство`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        medKits.delete(kit.id)
        dbHelper.flushAndClear()

        assertNull(medKits.findById(kit.id))
        assertNull(drugs.findById(drug.id), "препараты не переживают свою аптечку")
        assertTrue(medKits.findAllOfUser(alice.id).map { it.id }.isEmpty())
    }

    // ── Пользователи ─────────────────────────────────────────────────────────────

    @Test
    fun `снимку отдаются только идентификаторы аптечек`() {
        val alice = dbHelper.freshUser("alice")
        val outsider = dbHelper.freshUser("outsider")
        val mine = medKitService.create(alice.id)
        medKitService.create(outsider.id)
        dbHelper.flushAndClear()

        assertEquals(listOf(mine.id), medKits.findAllOfUser(alice.id).map { it.id })
    }

    /**
     * Массовый перенос обязан давать тот же исход, что и поштучный переезд через агрегат:
     * препараты в целевой аптечке, планы участников без доступа исчезли, остальные целы.
     */
    @Test
    fun `массовый перенос повторяет правило переезда препарата`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(source.id, alice.id), bob.id)
        val target = medKitService.create(alice.id)

        val first = dbHelper.freshDrug(source.id, 50.0)
        val second = dbHelper.freshDrug(source.id, 30.0)
        reservationService.create(alice.id, first.id, qty(10.0))
        reservationService.create(bob.id, first.id, qty(20.0))
        dbHelper.flushAndClear()

        reservations.deleteInMedKitExcept(source.id, setOf(alice.id))
        drugs.moveAllToMedKit(source.id, target.id)
        dbHelper.flushAndClear()

        assertEquals(target.id, dbHelper.requireDrug(first.id).medKitId)
        assertEquals(target.id, dbHelper.requireDrug(second.id).medKitId)
        assertQty(10.0, dbHelper.userReservation(alice.id, first.id))
        assertNull(dbHelper.userReservation(bob.id, first.id), "план без доступа не переезжает")
    }

    @Test
    fun `пользователь читается по идентификатору`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertEquals(alice.hashedKey, users.findById(alice.id)?.hashedKey)
        assertNull(users.findById(UUID.randomUUID()))
    }
}
