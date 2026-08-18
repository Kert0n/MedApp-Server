package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.InsufficientStock
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired

/**
 * Когда версия агрегата двигается, а когда нет.
 *
 * Без `@Transactional` намеренно: заявка на изменение корня применяется **перед коммитом**, а не
 * на flush, поэтому под обёрткой теста прирост версии аптечки был бы не виден вовсе. Каждый
 * вызов сервиса здесь — своя транзакция, как в приложении.
 *
 * Версия — токен состояния, а не счётчик команд: проверяется, что она сдвинулась, а не на
 * сколько именно. У аптечки, где заявка складывается с dirty checking, шаг и не равен единице.
 */
@PostgresIntegrationTest
class VersionRulesTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var medKits: MedKitStore
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    // ── Упаковка ─────────────────────────────────────────────────────────────────

    @Test
    fun `приём двигает версию упаковки`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val before = dbHelper.requireDrug(drug.id).version
        drugService.consume(drug.id, qty(10.0), alice.id, dbHelper.drugVersion(drug.id))

        assertTrue(dbHelper.requireDrug(drug.id).version > before, "версия обязана сдвинуться")
    }

    @Test
    fun `исправление количества двигает версию упаковки`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val before = dbHelper.requireDrug(drug.id).version
        drugService.update(drug.id, DrugPatchRequest(quantity = qty(80.0)), alice.id, dbHelper.drugVersion(drug.id))

        assertTrue(dbHelper.requireDrug(drug.id).version > before, "версия обязана сдвинуться")
    }

    /** Отказ откатывает транзакцию целиком, а версия — часть того же отката. */
    @Test
    fun `отклонённая команда версию не двигает`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 5.0)

        val before = dbHelper.requireDrug(drug.id).version
        assertFailsWith<InsufficientStock> { drugService.consume(drug.id, qty(50.0), alice.id, dbHelper.drugVersion(drug.id)) }

        assertEquals(before, dbHelper.requireDrug(drug.id).version, "версия после отказа та же")
    }

    /** Чтение — не команда: `GET` версии не касается, иначе она значила бы «кто-то смотрел». */
    @Test
    fun `чтение версию не двигает`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val before = dbHelper.requireDrug(drug.id).version
        orchestrator.drug(drug.id, alice.id)
        orchestrator.drugsAccessibleTo(alice.id)

        assertEquals(before, dbHelper.requireDrug(drug.id).version)
    }

    // ── Бронь ────────────────────────────────────────────────────────────────────

    @Test
    fun `изменение брони двигает её версию, но не версию упаковки`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        reservationService.create(alice.id, drug.id, qty(20.0))

        val reservationBefore = reservationService.require(alice.id, drug.id).version
        val drugBefore = dbHelper.requireDrug(drug.id).version

        reservationService.changeTo(alice.id, drug.id, qty(30.0), dbHelper.reservationVersion(alice.id, drug.id))

        assertTrue(
            reservationService.require(alice.id, drug.id).version > reservationBefore,
            "версия брони обязана сдвинуться"
        )
        assertEquals(
            drugBefore,
            dbHelper.requireDrug(drug.id).version,
            "упаковка не менялась: её версия чужой команде не принадлежит"
        )
    }

    @Test
    fun `приём версию брони не двигает`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        reservationService.create(alice.id, drug.id, qty(20.0))

        val before = reservationService.require(alice.id, drug.id).version
        drugService.consume(drug.id, qty(10.0), alice.id, dbHelper.drugVersion(drug.id))

        assertEquals(
            before,
            reservationService.require(alice.id, drug.id).version,
            "бронь не изменилась: сервер её не трогает"
        )
    }

    // ── Аптечка ──────────────────────────────────────────────────────────────────

    /**
     * Главный случай во всём наборе.
     *
     * Строка `med_kits` не меняется — меняется состав в другой таблице, — и dirty checking про
     * это не знает. Версию двигает заявка на изменение корня; без неё потерянное обновление
     * состава прошло бы незамеченным.
     */
    @Test
    fun `вступление двигает версию аптечки, хотя её строка не менялась`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)

        val before = requireKit(kit.id).version
        medKitService.join(kit.id, bob.id)

        assertTrue(requireKit(kit.id).version > before, "версия аптечки обязана сдвинуться")
    }

    @Test
    fun `выход участника двигает версию аптечки`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)

        val before = requireKit(kit.id).version
        orchestrator.leaveMedKit(kit.id, bob.id, dbHelper.medKitVersion(kit.id))

        assertTrue(requireKit(kit.id).version > before, "версия аптечки обязана сдвинуться")
    }

    @Test
    fun `отклонённое вступление версию аптечки не двигает`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)

        val before = requireKit(kit.id).version
        assertFailsWith<org.kert0n.medappserver.domain.AlreadyMember> {
            medKitService.join(kit.id, alice.id)
        }

        assertEquals(before, requireKit(kit.id).version, "версия после отказа та же")
    }

    // ── Массовый перенос ─────────────────────────────────────────────────────────

    /**
     * Единственное место, где версию двигает не Hibernate, а сам `UPDATE`.
     *
     * Сущностей в памяти нет, распорядиться версией Hibernate не может, поэтому `version =
     * version + 1` идёт частью оператора. Без этого упаковка уезжала бы в другую аптечку со
     * старой версией, и предъявленный клиентом токен продолжал бы считаться свежим.
     */
    @Test
    fun `массовый перенос двигает версии переехавших упаковок`() {
        val alice = dbHelper.freshUser("alice")
        val source = medKitService.create(alice.id)
        val target = medKitService.create(alice.id)
        val first = dbHelper.freshDrug(source.id, 10.0)
        val second = dbHelper.freshDrug(source.id, 20.0)

        val firstBefore = dbHelper.requireDrug(first.id).version
        val secondBefore = dbHelper.requireDrug(second.id).version

        orchestrator.delete(source.id, alice.id, dbHelper.medKitVersion(source.id), target.id)

        val movedFirst = dbHelper.requireDrug(first.id)
        val movedSecond = dbHelper.requireDrug(second.id)
        assertEquals(target.id, movedFirst.medKitId)
        assertTrue(movedFirst.version > firstBefore, "версия первой упаковки обязана сдвинуться")
        assertTrue(movedSecond.version > secondBefore, "версия второй упаковки обязана сдвинуться")
    }

    private fun requireKit(medKitId: UUID) = assertNotNull(medKits.findById(medKitId), "аптечка исчезла")
}
