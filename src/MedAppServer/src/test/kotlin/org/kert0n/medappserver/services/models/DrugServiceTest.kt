package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
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
class DrugServiceTest {

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var medKitService: MedKitService

    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `getAccessible returns immutable view for a member`() {
        val user = dbHelper.freshUser("drug-reader")
        val kit = medKitService.createNew(user.id)
        val drug = dbHelper.freshDrug(kit, 12.5)
        dbHelper.flushAndClear()

        val view = drugService.getAccessible(user.id, drug.id)

        assertEquals(drug.id, view.id)
        assertEquals(kit.id, view.medKitId)
        assertQty(12.5, view.quantity)
        assertQty(12.5, view.availableQuantity)
    }

    @Test
    fun `getAccessible conceals a drug from a non-member`() {
        val owner = dbHelper.freshUser("drug-owner")
        val outsider = dbHelper.freshUser("drug-outsider")
        val kit = medKitService.createNew(owner.id)
        val drug = dbHelper.freshDrug(kit, 10.0)
        dbHelper.flushAndClear()

        assertThrows<ResponseStatusException> {
            drugService.getAccessible(outsider.id, drug.id)
        }
    }
}
