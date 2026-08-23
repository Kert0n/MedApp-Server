package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.ReservationSyncRequest
import org.kert0n.medappserver.domain.InsufficientStock
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.orchestrator.StaleSyncVersion
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
 *
 * Вторая половина файла — про отказ, который наступает **в конце**. Предъявленную версию
 * сверяет предикат записи, то есть последний оператор команды: к этому моменту команда успела
 * переставить упаковки, снять чужие брони, уничтожить пачку. Проверяется не сам отказ — он под
 * тестом и так, — а что после него не осталось ничего.
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

        assertFailsWith<InsufficientStock> { drugs.recordIntake(drug.id, IntakeRequest(qty(11.0), version = 0), alice.id) }

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

        assertFailsWith<NotAMember> { drugs.moveToMedKit(drug.id, foreign.id, 0, alice.id) }

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

        assertFailsWith<NotAMember> { medKits.delete(kit.id, 0, eve.id) }

        assertNotNull(dbHelper.medKit(kit.id), "аптечка на месте")
        assertNotNull(dbHelper.drug(drug.id), "препарат на месте")
    }

    @Test
    fun `команда над несуществующим препаратом ничего не создаёт`() {
        val alice = dbHelper.freshUser("alice")
        dbHelper.freshMedKit(alice.id)

        assertFailsWith<NotAMember> { drugs.recordIntake(Uuid.random(), IntakeRequest(qty(1.0), version = 0), alice.id) }
    }

    // ── Отказ в конце команды ────────────────────────────────────────────────────

    /**
     * Самый длинный путь до отказа: упаковки уже переехали, чужие брони уже сняты.
     *
     * Версия аптечки предъявлена неверная, но узнаётся это последним оператором команды.
     * Вернуться обязаны обе части — и переезд, и снятые брони.
     */
    @Test
    fun `удаление аптечки с переносом откатывает и переезд, и снятые брони`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(source.id, 20.0)
        dbHelper.reserve(bob.id, drug.id, qty(5.0))

        assertFailsWith<StaleVersion> {
            medKits.delete(source.id, dbHelper.medKitVersion(source.id) + 1, alice.id, target.id)
        }

        assertNotNull(dbHelper.medKit(source.id), "аптечка на месте")
        assertEquals(source.id, dbHelper.requireDrug(drug.id).medKitId, "упаковка не переехала")
        assertQty(5.0, dbHelper.userReservation(bob.id, drug.id), "бронь Боба не снята")
    }

    /**
     * Переезд одной пачки: брони тех, кто цель не видит, снимаются раньше самого переезда.
     *
     * Отказ на переезде обязан вернуть их — иначе человек теряет бронь за команду, которая
     * не состоялась.
     */
    @Test
    fun `отказ на переезде возвращает снятые брони`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = dbHelper.freshMedKit(alice.id)
        dbHelper.join(source.id, alice.id, bob.id)
        val target = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(source.id, 20.0)
        dbHelper.reserve(bob.id, drug.id, qty(5.0))

        assertFailsWith<StaleVersion> {
            drugs.moveToMedKit(drug.id, target.id, dbHelper.drugVersion(drug.id) + 1, alice.id)
        }

        assertEquals(source.id, dbHelper.requireDrug(drug.id).medKitId, "упаковка не переехала")
        assertQty(5.0, dbHelper.userReservation(bob.id, drug.id), "бронь Боба на месте")
    }

    /**
     * Приём, опустошивший пачку: сначала снимаются брони, потом уничтожается упаковка.
     *
     * Отказ приходит на уничтожении — брони к этому моменту уже сняты.
     */
    @Test
    fun `отказ на уничтожении пустой пачки возвращает и пачку, и брони`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        assertFailsWith<StaleVersion> {
            drugs.recordIntake(drug.id, IntakeRequest(qty(10.0), version = dbHelper.drugVersion(drug.id) + 1), alice.id)
        }

        assertQty(10.0, dbHelper.drugQuantity(drug.id), "пачка цела и не тронута приёмом")
        assertQty(4.0, dbHelper.userReservation(alice.id, drug.id), "бронь на месте")
    }

    /** Выход последнего участника уносит аптечку с содержимым — отказ не уносит ничего. */
    @Test
    fun `отказ на выходе последнего участника ничего не удаляет`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)

        assertFailsWith<StaleVersion> { medKits.leave(kit.id, dbHelper.medKitVersion(kit.id) + 1, alice.id) }

        assertNotNull(dbHelper.medKit(kit.id), "аптечка на месте")
        assertNotNull(dbHelper.drug(drug.id), "препарат на месте")
    }

    /**
     * Синхронизация: списание и бронь одной транзакцией.
     *
     * Версия упаковки верная, версия картины броней — нет. Списание успевает произойти, и
     * отказ на второй части обязан отменить первую.
     */
    @Test
    fun `отказ на части про бронь отменяет списание`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        assertFailsWith<StaleSyncVersion> {
            drugs.synchronise(
                drug.id, Uuid.random(),
                DrugSyncRequest(
                    consumed = qty(3.0),
                    drugVersion = dbHelper.drugVersion(drug.id),
                    reservation = ReservationSyncRequest(
                        qty(2.0), version = dbHelper.reservationsVersion(drug.id, alice.id) + 1
                    )
                ),
                alice.id
            )
        }

        assertQty(10.0, dbHelper.drugQuantity(drug.id), "списание отменено")
        assertQty(4.0, dbHelper.userReservation(alice.id, drug.id), "бронь не изменилась")
    }

    /**
     * Журнал синхронизации не помнит откатившееся.
     *
     * Он живёт вне транзакции — пишется в `afterCommit` именно затем, чтобы откат его не
     * пережил. Отказ наступает последним оператором, уже после записи, поэтому проверяется
     * прямо: повтор с тем же идентификатором обязан выполнить команду, а не ответить «уже
     * применено».
     */
    @Test
    fun `откатившаяся синхронизация не считается применённой`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        val syncId = Uuid.random()

        assertFailsWith<StaleSyncVersion> {
            drugs.synchronise(
                drug.id, syncId,
                DrugSyncRequest(consumed = qty(3.0), drugVersion = dbHelper.drugVersion(drug.id) + 1),
                alice.id
            )
        }

        drugs.synchronise(
            drug.id, syncId,
            DrugSyncRequest(consumed = qty(3.0), drugVersion = dbHelper.drugVersion(drug.id)),
            alice.id
        )

        assertQty(7.0, dbHelper.drugQuantity(drug.id), "повтор выполнил команду, а не подтвердил её")
    }
}
