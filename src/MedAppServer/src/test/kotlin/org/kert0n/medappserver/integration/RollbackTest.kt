package org.kert0n.medappserver.integration

import org.kert0n.medappserver.services.models.ReservationService
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.InsufficientStock
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
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
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    /**
      * Бронь больше остатка теперь допустима, поэтому откат проверяется на том правиле,
      * которое осталось: съесть из пачки больше, чем в ней есть, нельзя.
      */
    @Test
    fun `отвергнутое списание не меняет пачку`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        reservationService.create(alice.id, drug.id, qty(4.0))

        assertFailsWith<InsufficientStock> { drugService.consume(drug.id, qty(11.0), alice.id) }

        assertQty(10.0, dbHelper.drugQuantity(drug.id))
        assertQty(4.0, dbHelper.userReservation(alice.id, drug.id))
    }

    /**
     * Перенос в чужую аптечку падает на проверке доступа — уже после того, как оркестратор
     * прочитал упаковку. Ни пачка, ни брони не должны сдвинуться.
     */
    @Test
    fun `перенос в недоступную аптечку не двигает ни пачку, ни брони`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val foreign = medKitService.create(eve.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        reservationService.create(alice.id, drug.id, qty(4.0))

        assertFailsWith<NotAMember> { orchestrator.moveDrug(drug.id, foreign.id, alice.id) }

        val stored = dbHelper.requireDrug(drug.id)
        assertEquals(kit.id, stored.medKitId, "упаковка осталась в своей аптечке")
        assertQty(4.0, dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `удаление чужой аптечки не удаляет её и не трогает препараты`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)

        assertFailsWith<NotAMember> { orchestrator.delete(kit.id, eve.id) }

        assertNotNull(medKitService.findById(kit.id), "аптечка на месте")
        assertNotNull(dbHelper.drug(drug.id), "препарат на месте")
    }

    @Test
    fun `команда над несуществующим препаратом ничего не создаёт`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.create(alice.id)

        assertFailsWith<NotAMember> { drugService.consume(UUID.randomUUID(), qty(1.0), alice.id) }
    }
}
