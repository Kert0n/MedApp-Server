package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import java.util.UUID
import org.kert0n.medappserver.api.SyncRequest
import org.kert0n.medappserver.api.SyncReservation
import org.kert0n.medappserver.domain.IntakeJournal
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.InterleavedTransactions
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.kert0n.medappserver.services.application.ReservationApplicationService

/**
 * Потерянное обновление не проходит.
 *
 * Каждый сценарий устроен одинаково: одна сторона читает состояние, вторая успевает изменить
 * его целиком, и только потом первая пишет по прочитанному. Победить обязана вторая — та, что
 * решала по актуальному состоянию, — а первая получить отказ.
 *
 * Проверяется не только отказ, но и **что осталось в базе**: без этого тест прошёл бы и в мире,
 * где обе стороны молча записали своё.
 */
@PostgresIntegrationTest
class ConcurrencyTest {

    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var medKits: MedKitApplicationService
    @Autowired private lateinit var drugStore: DrugStore
    @Autowired private lateinit var medKitStore: MedKitStore
    @Autowired private lateinit var reservationStore: ReservationStore
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugs: DrugApplicationService
    @Autowired private lateinit var journal: IntakeJournal
    @Autowired private lateinit var interleaved: InterleavedTransactions
    @Autowired private lateinit var reservationsApp: ReservationApplicationService

    /**
     * Двое едят из одной пачки.
     *
     * Оба видят сотню. Первый успевает съесть тридцать, второй считает по своей сотне и хочет
     * записать девяносто. Без версии в пачке оказалось бы девяносто — тридцать съеденных
     * растворились бы.
     */
    @Test
    fun `приём по устаревшему остатку отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val failure = interleaved.lostUpdate(
            read = { drugStore.findAccessible(drug.id, alice.id)!! },
            meanwhile = { drugService.consume(drug.id, qty(30.0), bob.id, dbHelper.drugVersion(drug.id)) },
            write = { stale -> drugStore.save(stale.consume(Quantity(qty(10.0), stale.quantity.unit))!!) }
        )

        assertNotNull(failure, "запись по устаревшему остатку обязана быть отклонена")
        assertQty(70.0, dbHelper.drugQuantity(drug.id)!!, "в пачке результат победившей команды")
    }

    /**
     * Двое последних выходят одновременно.
     *
     * Каждый видит состав из двоих и считает, что после его выхода останется второй. Без версии
     * в аптечке остался бы участник, который из неё вышел, — и это самый неприятный исход из
     * всех: доступ у того, кто его отдал.
     */
    @Test
    fun `выход по устаревшему составу отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)

        val failure = interleaved.lostUpdate(
            read = { medKitStore.findById(kit.id)!! },
            meanwhile = { medKits.leave(kit.id, bob.id, dbHelper.medKitVersion(kit.id)) },
            write = { stale -> medKitStore.save(stale.leave(alice.id)!!) }
        )

        assertNotNull(failure, "выход по устаревшему составу обязан быть отклонён")
        val left = assertNotNull(medKitStore.findById(kit.id), "аптечка на месте")
        assertEquals(setOf(alice.id), left.members, "в аптечке остался тот, кто не выходил")
    }

    /**
     * Перенос упаковки против выхода из целевой аптечки.
     *
     * Перенос решает по составу: кто увидит пачку после переезда, тот сохраняет бронь. Если в
     * этот момент участник выйдет, решение окажется принятым по составу, которого уже нет, — и
     * его бронь переживёт утрату доступа.
     *
     * Аптечку перенос не меняет, поэтому продвигать её версию ему нечем: он **требует, чтобы
     * состав не изменился** до коммита.
     *
     * Шаги повторяют `MedKitDrugOrchestrator.moveDrug`, а не вызывают его: метод атомарен, и
     * места, где его можно задержать между чтением состава и записью, в нём нет — вставлять
     * такое ради теста в рабочий код нельзя. Проверяется механизм; то, что перенос им
     * пользуется, видно в самом методе.
     */
    @Test
    fun `перенос против выхода из целевой аптечки отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = medKitService.create(alice.id)
        medKitService.join(source.id, bob.id)
        val target = medKitService.create(alice.id)
        medKitService.join(target.id, bob.id)

        val drug = dbHelper.freshDrug(source.id, 100.0)
        reservationService.create(bob.id, drug.id, qty(20.0))

        val failure = interleaved.lostUpdate(
            read = {
                val kit = medKitStore.findById(target.id)!!
                medKitStore.requireUnchanged(kit)
                kit
            },
            meanwhile = { medKits.leave(target.id, bob.id, dbHelper.medKitVersion(target.id)) },
            write = { stale ->
                drugStore.save(drugStore.findAccessible(drug.id, alice.id)!!.moveTo(stale.id))
                reservationStore.deleteOfDrugExcept(drug.id, stale.members)
            }
        )

        assertNotNull(failure, "перенос по устаревшему составу обязан быть отклонён")
        assertEquals(source.id, dbHelper.requireDrug(drug.id).medKitId, "пачка осталась на месте")
        assertNotNull(
            dbHelper.userReservation(bob.id, drug.id),
            "бронь Боба цела: он всё ещё видит пачку в исходной аптечке"
        )
    }

    // ── Журнал синхронизаций ─────────────────────────────────────────────────────

    /**
     * Откатившаяся синхронизация не остаётся в журнале.
     *
     * Кеш не участвует в транзакции: запись «по ходу» пережила бы откат, и повтор получил бы
     * подтверждение того, чего в базе нет. Хуже всего то, как это выглядит для человека —
     * клиент видит 200, считает офлайн-очередь применённой и чистит её, а таблетки не списаны.
     *
     * Отказ приходит не изнутри команды, а с flush: обе стороны прочитали версию 3, вторая
     * успела записать, и `UPDATE ... WHERE version = 3` не задевает ни одной строки.
     */
    @Test
    fun `откатившаяся синхронизация не остаётся в журнале`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        val staleVersion = dbHelper.drugVersion(drug.id)
        val syncId = UUID.randomUUID()

        val failure = interleaved.lostUpdate(
            // Упаковка попадает в persistence context до чужой записи: дальше синхронизация
            // увидит именно её, со своей версией 3.
            read = { drugStore.findAccessible(drug.id, alice.id)!! },
            meanwhile = { drugService.consume(drug.id, qty(30.0), bob.id, staleVersion) },
            write = {
                drugs.synchronise(
                    syncId, drug.id, SyncRequest(consumed = qty(5.0), drugVersion = staleVersion), alice.id
                )
            }
        )

        assertNotNull(failure, "синхронизация по устаревшей версии обязана быть отклонена")
        assertQty(70.0, dbHelper.drugQuantity(drug.id)!!, "в пачке результат победившей команды")
        assertNull(journal.find(syncId), "неудачной попытки в журнале быть не должно")
    }

    /** И повтор того же запроса после отказа применяется по-настоящему, а не отвечает согласием. */
    @Test
    fun `повтор после отката списывает по-настоящему`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        val staleVersion = dbHelper.drugVersion(drug.id)
        val syncId = UUID.randomUUID()

        interleaved.lostUpdate(
            read = { drugStore.findAccessible(drug.id, alice.id)!! },
            meanwhile = { drugService.consume(drug.id, qty(30.0), bob.id, staleVersion) },
            write = {
                drugs.synchronise(
                    syncId, drug.id, SyncRequest(consumed = qty(5.0), drugVersion = staleVersion), alice.id
                )
            }
        )

        // Клиент перечитал пачку и повторил тем же идентификатором — так и задуман `syncId`.
        drugs.synchronise(
            syncId,
            drug.id,
            SyncRequest(consumed = qty(5.0), drugVersion = dbHelper.drugVersion(drug.id)),
            alice.id
        )

        assertQty(65.0, dbHelper.drugQuantity(drug.id)!!, "повтор списал те самые пять")
    }

    // ── Решение по чужому агрегату ───────────────────────────────────────────────

    /**
     * Бронь не появляется у того, кто в этот момент вышел из аптечки.
     *
     * Опасно здесь не то, что бронь заведена без права, а то, что её **некому убрать**: уборщик
     * броней при выходе отработал по составу на свой момент, и запись, легшая следом, не видна
     * больше ни одному сценарию очистки.
     *
     * Шаги повторяют `createReservation`, а не вызывают его: метод атомарен, и места, где его
     * можно задержать между решением и записью, в нём нет.
     *
     * Запись идёт прямо в хранилище намеренно. Проверка доступа внутри `create` окно **сужает**
     * — до промежутка между последним чтением членства и коммитом, — но не закрывает: чужой
     * выход успевает закоммититься и там. Закрывает его требование не меняться, и тест
     * показывает ровно это: убери `requireUnchanged` из шагов ниже, и бронь ляжет.
     */
    @Test
    fun `бронь против выхода из аптечки не остаётся без доступа`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val failure = interleaved.lostUpdate(
            read = {
                val visible = drugService.require(drug.id, bob.id)
                drugService.requireUnchanged(visible)
                medKitStore.requireUnchanged(medKitService.requireAccessible(visible.medKitId, bob.id))
                visible
            },
            meanwhile = { medKits.leave(kit.id, bob.id, dbHelper.medKitVersion(kit.id)) },
            write = { visible -> reservationStore.insert(Reservation(bob.id, drug.id, visible.quantity)) }
        )

        assertNotNull(failure, "бронь по устаревшему составу обязана быть отклонена")
        assertNull(dbHelper.userReservation(bob.id, drug.id), "брони без доступа не осталось")
    }

    /** То же для переезда пачки: решение принималось по упаковке, которой в этой аптечке уже нет. */
    @Test
    fun `бронь против переноса упаковки не остаётся без доступа`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val shared = medKitService.create(alice.id)
        medKitService.join(shared.id, bob.id)
        val private = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(shared.id, 100.0)

        val failure = interleaved.lostUpdate(
            read = {
                val visible = drugService.require(drug.id, bob.id)
                drugService.requireUnchanged(visible)
                medKitStore.requireUnchanged(medKitService.requireAccessible(visible.medKitId, bob.id))
                visible
            },
            meanwhile = {
                drugs.moveToMedKit(drug.id, private.id, alice.id, dbHelper.drugVersion(drug.id))
            },
            write = { visible -> reservationStore.insert(Reservation(bob.id, drug.id, visible.quantity)) }
        )

        assertNotNull(failure, "бронь на уехавшую пачку обязана быть отклонена")
        assertNull(dbHelper.userReservation(bob.id, drug.id), "брони без доступа не осталось")
    }

    /**
     * Синхронизация «только бронь» держит упаковку, хотя её не пишет.
     *
     * `drugVersion` в теле сравнивается, но без приёма упаковка не записывается — и сама себя
     * версия не проверит. Без требования не меняться предусловие было бы украшением.
     */
    @Test
    fun `синхронизация только брони против правки упаковки отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        reservationService.create(alice.id, drug.id, qty(20.0))
        val staleDrugVersion = dbHelper.drugVersion(drug.id)
        val reservationVersion = dbHelper.reservationVersion(alice.id, drug.id)

        val failure = interleaved.lostUpdate(
            read = { drugStore.findAccessible(drug.id, alice.id)!! },
            meanwhile = { drugService.consume(drug.id, qty(30.0), bob.id, staleDrugVersion) },
            write = {
                drugs.synchronise(
                    UUID.randomUUID(),
                    drug.id,
                    SyncRequest(
                        drugVersion = staleDrugVersion,
                        reservation = SyncReservation(amount = qty(45.0), version = reservationVersion)
                    ),
                    alice.id
                )
            }
        )

        assertNotNull(failure, "синхронизация по устаревшей упаковке обязана быть отклонена")
        assertQty(20.0, dbHelper.userReservation(alice.id, drug.id)!!, "бронь не тронута")
    }

    // ── Ожидаемые гонки создания ─────────────────────────────────────────────────

    /**
     * Две одновременные брони на одну пачку: одна ложится, вторая получает правило.
     *
     * Проверка «уже есть?» перед вставкой ловит только последовательный случай — одновременный
     * доходит до базы, и там нарушается первичный ключ. Это ожидаемый исход гонки, а не поломка
     * сервера, поэтому перевод идёт по **имени** ограничения: `reservations_pkey` значит «такая
     * бронь уже есть», а незнакомое ограничение обязано остаться серверной ошибкой.
     */
    @Test
    fun `одновременное заведение брони отвергается правилом, а не пятисоткой`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)

        val failure = interleaved.lostUpdate(
            read = { drugService.require(drug.id, alice.id) },
            meanwhile = { reservationsApp.create(alice.id, drug.id, qty(20.0)) },
            write = { visible -> reservationStore.insert(Reservation(alice.id, drug.id, visible.quantity)) }
        )

        assertIs<ReservationAlreadyExists>(
            assertNotNull(failure, "вторая бронь обязана быть отклонена"),
            "гонка создания — правило домена, а не сбой сервера"
        )
        assertQty(20.0, dbHelper.userReservation(alice.id, drug.id)!!, "осталась первая бронь")
    }

    /**
     * Вступление в аптечку, которой уже нет, отвечает «нет такой аптечки».
     *
     * Здесь целостность держит внешний ключ, а не версия: строка членства вставляется раньше,
     * чем заявка на изменение корня доходит до базы. Замерено снятием обеих заявок у аптечки —
     * сценарий отвергается и без них, в отличие от остальных гонок. Раньше отказ приходил
     * наружу нарушением целостности, то есть пятисоткой; теперь — тем же, чем отвечает любая
     * недоступная аптечка.
     */
    @Test
    fun `вступление в удалённую аптечку отвечает как на недоступную`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)

        val failure = interleaved.lostUpdate(
            read = { medKitStore.findById(kit.id)!! },
            meanwhile = { medKits.delete(kit.id, alice.id, dbHelper.medKitVersion(kit.id)) },
            write = { stale -> medKitStore.save(stale.join(bob.id)) }
        )

        assertIs<NotAMember>(
            assertNotNull(failure, "вступление обязано быть отклонено"),
            "исчезнувшая аптечка — это 404, а не сбой сервера"
        )
        assertNull(medKitStore.findById(kit.id), "аптечки нет")
        assertEquals(emptyList(), medKitStore.findAllOfUser(bob.id), "и членства в ней тоже")
    }

    // ── Массовый перенос ─────────────────────────────────────────────────────────

    /**
     * Удаление аптечки с переносом держит состав целевой так же, как поштучный переезд.
     *
     * Отдельный тест, а не повторение предыдущего: между заявкой на состав и коммитом здесь
     * стоит массовый `UPDATE` с `clearAutomatically`, который чистит persistence context. Живёт
     * ли зарегистрированная проверка версии после очистки — вопрос к Hibernate, и отвечать на
     * него надо замером, а не чтением документации по памяти.
     */
    @Test
    fun `удаление с переносом против выхода из целевой аптечки отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = medKitService.create(alice.id)
        medKitService.join(source.id, bob.id)
        val target = medKitService.create(alice.id)
        medKitService.join(target.id, bob.id)

        val drug = dbHelper.freshDrug(source.id, 100.0)
        reservationService.create(bob.id, drug.id, qty(20.0))
        val sourceVersion = dbHelper.medKitVersion(source.id)

        val failure = interleaved.lostUpdate(
            read = {
                val kit = medKitStore.findById(target.id)!!
                medKitStore.requireUnchanged(kit)
                kit
            },
            meanwhile = { medKits.leave(target.id, bob.id, dbHelper.medKitVersion(target.id)) },
            write = { stale ->
                reservationStore.deleteInMedKitExcept(source.id, stale.members)
                drugStore.moveAllToMedKit(source.id, stale.id)
                medKitStore.requireUnchanged(stale)
                medKitService.delete(source.id, alice.id, sourceVersion)
            }
        )

        assertNotNull(failure, "перенос по устаревшему составу обязан быть отклонён")
        assertEquals(source.id, dbHelper.requireDrug(drug.id).medKitId, "пачка осталась в исходной")
        assertNotNull(dbHelper.userReservation(bob.id, drug.id), "бронь Боба цела")
    }

    /**
     * Заведение брони действительно удерживает упаковку, а не только умеет это в принципе.
     *
     * Остальные гонки проверяют механизм на уровне хранилища и до самой команды не доходят —
     * снял гарды из `ReservationService.create`, и ни один тест не заметил. Здесь команда
     * зовётся целиком, а обе аптечки доступны Бобу: доступ к пачке после переезда сохраняется,
     * и отвергнуть заведение может **только** удержание версии упаковки.
     */
    @Test
    fun `заведение брони удерживает упаковку, по которой решало`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val source = medKitService.create(alice.id)
        medKitService.join(source.id, bob.id)
        val target = medKitService.create(alice.id)
        medKitService.join(target.id, bob.id)
        val drug = dbHelper.freshDrug(source.id, 100.0)

        val failure = interleaved.lostUpdate(
            // Упаковка попадает в persistence context до чужой записи: команда ниже увидит её
            // со старой версией и обязана на этом споткнуться.
            read = { drugStore.findAccessible(drug.id, bob.id)!! },
            meanwhile = { drugs.moveToMedKit(drug.id, target.id, alice.id, dbHelper.drugVersion(drug.id)) },
            write = { reservationsApp.create(bob.id, drug.id, qty(20.0)) }
        )

        assertNotNull(failure, "бронь по устаревшей упаковке обязана быть отклонена")
        assertNull(dbHelper.userReservation(bob.id, drug.id), "брони не осталось")
    }
}
