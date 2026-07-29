package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.repository.DrugRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Контракты команд TreatmentPlan на границе агрегатов Using и Drug. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TreatmentPlanServiceTest {

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var medKitFixture: MedKitFixture
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper
    @Autowired
    private lateinit var drugRepository: DrugRepository

    // ── create ──

    @Test
    fun `create creates plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val using = treatmentPlanService.create(alice.id, drug.id, qty(30.0))

        assertQty(30.0, using.plannedAmount)
        assertEquals(alice.id, using.userId)
        assertEquals(drug.id, using.drugId)
    }

    @Test
    fun `create throws CONFLICT for duplicate`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.create(alice.id, drug.id, qty(20.0))
        }
    }

    @Test
    fun `create throws when exceeding available quantity`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 50.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.create(alice.id, drug.id, qty(100.0))
        }
    }

    // ── update ──

    @Test
    fun `update updates planned amount`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        val updated = treatmentPlanService.patch(alice.id, drug.id, qty(50.0))
        assertQty(50.0, updated.plannedAmount)
    }

    @Test
    fun `update throws when exceeding available quantity`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitFixture.createNew(alice.id)
        medKitFixture.joinMedKitByKey(medKitFixture.generateMedKitShareKey(kit.id, alice.id), bob.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(50.0))
        treatmentPlanService.create(bob.id, drug.id, qty(30.0))
        dbHelper.flushAndClear()

        // Bob tries to increase to 60 but only 100 - 50 = 50 available for him
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.patch(bob.id, drug.id, qty(60.0))
        }
    }

    @Test
    fun `zero amount is not an alias for deletion`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.create(alice.id, drug.id, qty(0.0))
        }
        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.patch(alice.id, drug.id, qty(0.0))
        }
    }

    @Test
    fun `delete and intake return explicit plan lifecycle`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()

        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        val remaining = treatmentPlanService.applyIntake(alice.id, drug.id, qty(4.0))
        assertQty(6.0, remaining?.plannedAmount)

        treatmentPlanService.delete(alice.id, drug.id)
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.delete(alice.id, drug.id)
        }
    }

    @Test
    fun `missing or foreign plan is never disclosed`() {
        val alice = dbHelper.freshUser("alice")
        val outsider = dbHelper.freshUser("outsider")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()
        treatmentPlanService.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.patch(outsider.id, drug.id, qty(5.0))
        }
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.delete(outsider.id, drug.id)
        }
        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.delete(alice.id, java.util.UUID.randomUUID())
        }

        assertQty(10.0, dbHelper.userPlan(alice.id, drug.id))
        assertNull(dbHelper.userPlan(outsider.id, drug.id))
    }

    @Test
    fun `rejected intake does not change stock or plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 20.0)
        dbHelper.flushAndClear()
        treatmentPlanService.create(alice.id, drug.id, qty(5.0))
        dbHelper.flushAndClear()

        assertFailsWith<ResponseStatusException> {
            treatmentPlanService.applyIntake(alice.id, drug.id, qty(6.0))
        }
        dbHelper.flushAndClear()

        assertQty(20.0, drugRepository.findById(drug.id).orElseThrow().quantity)
        assertQty(5.0, dbHelper.userPlan(alice.id, drug.id))
    }
}
