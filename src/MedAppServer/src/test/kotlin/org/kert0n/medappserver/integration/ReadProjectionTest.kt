package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.testutil.*
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Формы чтения считают суммы планов в базе.
 *
 * Раньше сумма приходила полем `@Formula` внутри препарата: чтобы получить одно число,
 * загружалась сущность, а на списках — по запросу на каждый препарат. Здесь проверяется,
 * что суммы верны, что доступный остаток считается от них же, и что чужие планы в чужую
 * сумму не попадают.
 */
@PostgresIntegrationTest
@Transactional
class ReadProjectionTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `сумма планов складывается по всем участникам`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        drugService.createPlanLatest(alice.id, drug.id, qty(30.0))
        drugService.createPlanLatest(bob.id, drug.id, qty(20.0))
        dbHelper.flushAndClear()

        val view = drugService.require(drug.id, alice.id)

        assertQty(100.0, view.quantity)
        assertQty(50.0, view.plannedTotal)
        // Доступный остаток — разность, посчитанная от той же пары чисел, а не отдельным
        // запросом: иначе можно было увидеть остаток и планы из разных моментов времени.
        assertQty(50.0, view.availableQuantity)
    }

    @Test
    fun `препарат без планов отдаёт нулевую сумму, а не отсутствие строки`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 7.0)
        dbHelper.flushAndClear()

        // Джойн с планами внешний: препарат без единого плана обязан остаться в выдаче.
        val view = drugService.require(drug.id, alice.id)

        assertQty(0.0, view.plannedTotal)
        assertQty(7.0, view.availableQuantity)
    }

    @Test
    fun `чужой препарат не читается`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.flushAndClear()

        assertNull(drugService.find(drug.id, eve.id), "чужая аптечка не должна читаться")
    }

    @Test
    fun `снимок собирает препараты всех аптечек пользователя одним запросом`() {
        val alice = dbHelper.freshUser("alice")
        val first = medKitService.create(alice.id)
        val second = medKitService.create(alice.id)
        val outsider = dbHelper.freshUser("outsider")
        val foreign = medKitService.create(outsider.id)

        dbHelper.freshDrug(first.id, 1.0)
        dbHelper.freshDrug(first.id, 2.0)
        dbHelper.freshDrug(second.id, 3.0)
        dbHelper.freshDrug(foreign.id, 4.0)
        dbHelper.flushAndClear()

        val views = drugService.accessibleTo(alice.id)

        assertEquals(3, views.size, "видны препараты обеих своих аптечек и только их")
        assertEquals(2, views.count { it.medKitId == first.id })
        assertEquals(1, views.count { it.medKitId == second.id })
        assertTrue(views.none { it.medKitId == foreign.id }, "чужая аптечка в снимок не попадает")
    }

    @Test
    fun `план читается только своим владельцем`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val bob = dbHelper.freshUser("bob")
        medKitService.joinByInvitation(medKitService.invite(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        drugService.createPlanLatest(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        assertQty(30.0, treatmentPlanService.requirePlan(alice.id, drug.id).plan.plannedAmount)
        // Препарат общий, план — личный: Боб видит препарат, но не чужой план.
        assertNull(treatmentPlanService.findPlan(bob.id, drug.id), "чужой план не читается")
    }
}
