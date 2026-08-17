package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.PlannedAmountExceedsStock
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.*
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired

/**
 * Упавшая команда не оставляет следов.
 *
 * Тесты намеренно **без** `@Transactional` на классе: обёртка теста откатывала бы всё сама, и
 * проверка стала бы бессмысленной — она подтверждала бы работу тестовой обёртки, а не
 * транзакционных границ приложения.
 */
@PostgresIntegrationTest
class RollbackTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `отвергнутый план не меняет остаток и не оставляет строк`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)

        assertFailsWith<PlannedAmountExceedsStock> {
            drugService.createPlanLatest(alice.id, drug.id, qty(11.0))
        }

        assertQty(10.0, dbHelper.drugQuantity(drug.id))
        assertEquals(emptyList(), dbHelper.requireDrug(drug.id).plans)
    }

    /**
     * Перенос в чужую аптечку падает на проверке доступа — уже после того, как оркестратор
     * прочитал препарат. Ни препарат, ни планы не должны сдвинуться.
     */
    @Test
    fun `перенос в недоступную аптечку не двигает ни препарат, ни планы`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val foreign = medKitService.create(eve.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        drugService.createPlanLatest(alice.id, drug.id, qty(4.0))

        assertFailsWith<NotAMember> { orchestrator.moveDrugLatest(drugService, drug.id, foreign.id, alice.id) }

        val stored = dbHelper.requireDrug(drug.id)
        assertEquals(kit.id, stored.medKitId, "препарат остался в своей аптечке")
        assertQty(4.0, dbHelper.userPlan(alice.id, drug.id))
    }

    @Test
    fun `удаление чужой аптечки не удаляет её и не трогает препараты`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)

        assertFailsWith<NotAMember> { orchestrator.deleteLatest(medKitService, kit.id, eve.id) }

        assertNotNull(medKitService.findById(kit.id), "аптечка на месте")
        assertNotNull(dbHelper.drug(drug.id), "препарат на месте")
    }

    @Test
    fun `команда над несуществующим препаратом ничего не создаёт`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.create(alice.id)

        assertFailsWith<NotAMember> { drugService.consumeLatest(UUID.randomUUID(), qty(1.0), alice.id) }
    }
}
