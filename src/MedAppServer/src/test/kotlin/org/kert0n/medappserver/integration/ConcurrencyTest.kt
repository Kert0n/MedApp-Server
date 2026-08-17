package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.ReservationService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.InterleavedTransactions
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired

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
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var drugs: DrugStore
    @Autowired private lateinit var medKits: MedKitStore
    @Autowired private lateinit var reservations: ReservationStore
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var interleaved: InterleavedTransactions

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
            read = { drugs.findAccessible(drug.id, alice.id)!! },
            meanwhile = { drugService.consume(drug.id, qty(30.0), bob.id, dbHelper.drugVersion(drug.id)) },
            write = { stale -> drugs.save(stale.consume(Quantity(qty(10.0), stale.quantity.unit))!!) }
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
            read = { medKits.findById(kit.id)!! },
            meanwhile = { orchestrator.leaveMedKit(kit.id, bob.id, dbHelper.medKitVersion(kit.id)) },
            write = { stale -> medKits.save(stale.leave(alice.id)!!) }
        )

        assertNotNull(failure, "выход по устаревшему составу обязан быть отклонён")
        val left = assertNotNull(medKits.findById(kit.id), "аптечка на месте")
        assertEquals(setOf(alice.id), left.members, "в аптечке остался тот, кто не выходил")
    }

    /**
     * Вступление в аптечку, которой уже нет.
     *
     * Приглашение живёт своё время и переживает саму аптечку: вступающий видит её состав, пока
     * последний участник её удаляет. Проверяется исход — членства в несуществующей аптечке не
     * остаётся.
     *
     * Держит это **внешний ключ, а не версия**: строка членства вставляется раньше, чем заявка
     * на изменение корня доходит до базы, и падает первой. Замерено снятием обеих заявок —
     * сценарий отвергается и без них, в отличие от трёх остальных. Цена известна: отказ
     * приходит как нарушение целостности, а не как конфликт, и наружу это пока 500. Код ответа
     * — предмет коммита про предусловия.
     */
    @Test
    fun `вступление в удалённую аптечку отклоняется`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)

        val failure = interleaved.lostUpdate(
            read = { medKits.findById(kit.id)!! },
            meanwhile = { orchestrator.delete(kit.id, alice.id, dbHelper.medKitVersion(kit.id)) },
            write = { stale -> medKits.save(stale.join(bob.id)) }
        )

        assertNotNull(failure, "вступление в удалённую аптечку обязано быть отклонено")
        assertNull(medKits.findById(kit.id), "аптечки нет")
        assertEquals(emptyList(), medKits.findAllOfUser(bob.id), "и членства в ней тоже")
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
                val kit = medKits.findById(target.id)!!
                medKits.requireUnchanged(kit)
                kit
            },
            meanwhile = { orchestrator.leaveMedKit(target.id, bob.id, dbHelper.medKitVersion(target.id)) },
            write = { stale ->
                drugs.save(drugs.findAccessible(drug.id, alice.id)!!.moveTo(stale.id))
                reservations.deleteOfDrugExcept(drug.id, stale.members)
            }
        )

        assertNotNull(failure, "перенос по устаревшему составу обязан быть отклонён")
        assertEquals(source.id, dbHelper.requireDrug(drug.id).medKitId, "пачка осталась на месте")
        assertNotNull(
            dbHelper.userReservation(bob.id, drug.id),
            "бронь Боба цела: он всё ещё видит пачку в исходной аптечке"
        )
    }
}
