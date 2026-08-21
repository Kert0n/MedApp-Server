package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.InsufficientStock
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
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

    @Autowired private lateinit var drugs: DrugApplicationService
    @Autowired private lateinit var medKits: MedKitApplicationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    /**
     * Бронь больше остатка допустима, поэтому откат проверяется на том правиле, которое
     * осталось: съесть из пачки больше, чем в ней есть, нельзя.
     */
    @Test
    fun `отвергнутое списание не меняет пачку`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        assertFailsWith<InsufficientStock> { drugs.recordIntake(drug.id, qty(11.0), alice.id) }

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
        val kit = dbHelper.freshMedKit(alice.id)
        val foreign = dbHelper.freshMedKit(eve.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        assertFailsWith<NotAMember> { drugs.moveToMedKit(drug.id, foreign.id, alice.id) }

        val stored = dbHelper.requireDrug(drug.id)
        assertEquals(kit.id, stored.medKitId, "упаковка осталась в своей аптечке")
        assertQty(4.0, dbHelper.userReservation(alice.id, drug.id))
    }

    @Test
    fun `удаление чужой аптечки не удаляет её и не трогает препараты`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)

        assertFailsWith<NotAMember> { medKits.delete(kit.id, eve.id) }

        assertNotNull(dbHelper.medKit(kit.id), "аптечка на месте")
        assertNotNull(dbHelper.drug(drug.id), "препарат на месте")
    }

    @Test
    fun `команда над несуществующим препаратом ничего не создаёт`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.freshMedKit(alice.id)

        assertFailsWith<NotAMember> { drugs.recordIntake(Uuid.random(), qty(1.0), alice.id) }
    }
}
