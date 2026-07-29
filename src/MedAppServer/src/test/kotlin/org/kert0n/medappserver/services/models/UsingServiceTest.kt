package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UsingServiceTest {

    @Autowired
    private lateinit var usingService: UsingService

    @Autowired
    private lateinit var treatmentPlans: TreatmentPlanService

    @Autowired
    private lateinit var medKitFixture: MedKitFixture

    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `listForUser returns only immutable plan views of that user`() {
        val alice = dbHelper.freshUser("plan-reader")
        val kit = medKitFixture.createNew(alice.id)
        val drug = dbHelper.freshDrug(kit, 100.0)
        treatmentPlans.create(alice.id, drug.id, qty(10.0))
        dbHelper.flushAndClear()

        val result = usingService.listForUser(alice.id)

        assertEquals(1, result.size)
        assertEquals(alice.id, result.single().userId)
        assertEquals(drug.id, result.single().drugId)
        assertQty(10.0, result.single().plannedAmount)
    }

    @Test
    fun `getForUser returns 404 for a missing plan`() {
        val alice = dbHelper.freshUser("plan-missing")

        assertThrows<ResponseStatusException> {
            usingService.getForUser(alice.id, java.util.UUID.randomUUID())
        }
    }
}
