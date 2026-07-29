package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Заведение и правка плана лечения.
 *
 * Проверки переехали сюда из UsingServiceTest вместе с самими операциями: они стоят на стыке
 * плана и препарата — читают остаток чужого агрегата под блокировкой, — и модельному сервису
 * связи не принадлежат.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TreatmentPlanServiceTest {

    @Autowired
    private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── create ──

    @Test
    fun `create creates plan`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        dbHelper.flushAndClear()

        val using = treatmentPlanService.create(alice.id, drug.id, qty(30.0))

        assertQty(30.0, using.plannedAmount)
        assertEquals(alice.id, using.user.id)
        assertEquals(drug.id, using.drug.id)
    }

    @Test
    fun `create throws CONFLICT for duplicate`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
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
        val kit = medKitService.createNew(alice.id)
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
        val kit = medKitService.createNew(alice.id)
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
        val kit = medKitService.createNew(alice.id)
        medKitService.joinMedKitByKey(medKitService.generateMedKitShareKey(kit.id, alice.id), bob.id)
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
        val kit = medKitService.createNew(alice.id)
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
        val kit = medKitService.createNew(alice.id)
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
}
