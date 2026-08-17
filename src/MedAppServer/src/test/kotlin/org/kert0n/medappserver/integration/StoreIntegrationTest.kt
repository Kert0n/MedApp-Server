package org.kert0n.medappserver.integration

import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.*
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
    @Autowired private lateinit var medKits: MedKitStore
    @Autowired private lateinit var users: UserStore
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    // ── Препараты ────────────────────────────────────────────────────────────────

    @Test
    fun `препараты аптечки читаются вместе со своими планами`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val first = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.freshDrug(kit.id, 20.0)
        drugService.createPlanLatest(alice.id, first.id, qty(4.0))
        dbHelper.flushAndClear()

        val loaded = drugs.findAllInMedKit(kit.id)

        assertEquals(2, loaded.size)
        assertQty(4.0, loaded.single { it.id == first.id }.plannedTotal)
        assertQty(6.0, loaded.single { it.id == first.id }.availableQuantity)
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

    /**
     * Отдельной загрузки «под блокировку» больше нет: её место заняла версия, и команда
     * читает препарат тем же запросом, что и чтение. Проверяется здесь то, ради чего замена
     * делалась, — что версия доезжает из строки в доменное состояние и растёт от записи.
     */
    @Test
    fun `команда читает препарат вместе с версией`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 30.0)
        dbHelper.flushAndClear()

        val fresh = drugs.findAccessible(drug.id, alice.id)!!
        assertEquals(0L, fresh.version, "новый препарат приходит с нулевой версией")

        drugService.createPlanLatest(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val afterPlan = drugs.findAccessible(drug.id, alice.id)!!
        assertQty(30.0, afterPlan.quantity)
        assertQty(10.0, afterPlan.plannedTotal)
        assertEquals(1L, afterPlan.version, "план — часть препарата, значит версия корня выросла")
    }

    // ── Планы ────────────────────────────────────────────────────────────────────

    @Test
    fun `планы участника собираются по всем препаратам`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val first = dbHelper.freshDrug(kit.id, 50.0)
        val second = dbHelper.freshDrug(kit.id, 50.0)
        drugService.createPlanLatest(alice.id, first.id, qty(5.0))
        drugService.createPlanLatest(alice.id, second.id, qty(7.0))
        dbHelper.flushAndClear()

        val plans = drugs.findPlansOf(alice.id)

        assertEquals(2, plans.size)
        assertQty(5.0, plans.single { it.plan.drugId == first.id }.plan.plannedAmount)
    }

    @Test
    fun `чужой план не читается по паре участник-препарат`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        drugService.createPlanLatest(alice.id, drug.id, qty(5.0))
        dbHelper.flushAndClear()

        assertQty(5.0, drugs.findPlan(alice.id, drug.id)?.plan?.plannedAmount)
        assertNull(drugs.findPlan(bob.id, drug.id), "у Боба плана нет")
    }

    @Test
    fun `сумма планов препарата без планов равна нулю`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 50.0)
        dbHelper.flushAndClear()

        assertQty(0.0, drugs.findById(drug.id)!!.plannedTotal)
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

        assertEquals(listOf(mine.id), medKits.findRefsOfUser(alice.id).map { it.id })
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

        val overview = medKits.overviewsOf(alice.id).single()

        assertEquals(2, overview.memberCount)
        assertEquals(3, overview.drugCount)
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
        assertTrue(medKits.findRefsOfUser(alice.id).isEmpty())
    }

    // ── Пользователи ─────────────────────────────────────────────────────────────

    @Test
    fun `снимку отдаются только идентификаторы аптечек`() {
        val alice = dbHelper.freshUser("alice")
        val outsider = dbHelper.freshUser("outsider")
        val mine = medKitService.create(alice.id)
        medKitService.create(outsider.id)
        dbHelper.flushAndClear()

        assertEquals(listOf(mine.id), medKits.findRefsOfUser(alice.id).map { it.id })
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
        drugService.createPlanLatest(alice.id, first.id, qty(10.0))
        drugService.createPlanLatest(bob.id, first.id, qty(20.0))
        dbHelper.flushAndClear()

        drugs.moveAllToMedKit(source.id, target.id, setOf(alice.id))
        dbHelper.flushAndClear()

        assertEquals(target.id, dbHelper.requireDrug(first.id).medKitId)
        assertEquals(target.id, dbHelper.requireDrug(second.id).medKitId)
        assertQty(10.0, dbHelper.userPlan(alice.id, first.id))
        assertNull(dbHelper.userPlan(bob.id, first.id), "план без доступа не переезжает")
    }

    @Test
    fun `пользователь читается по идентификатору`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.flushAndClear()

        assertEquals(alice.hashedKey, users.findById(alice.id)?.hashedKey)
        assertNull(users.findById(UUID.randomUUID()))
    }
}
