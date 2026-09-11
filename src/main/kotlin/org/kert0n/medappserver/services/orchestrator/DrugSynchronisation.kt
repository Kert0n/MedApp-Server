package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Съеденное и новая бронь применяются вместе.
 *
 * Клиент копит изменения офлайн и присылает итог, а не поток событий. Двумя запросами это
 * послать нельзя: порядок между ними не гарантирован, и между списанием и уменьшением брони
 * остаётся окно, в котором срабатывают уведомления «лекарства мало».
 *
 * Съеденное — дельта: она коммутативна, и складывать её можно в любом порядке. Бронь —
 * абсолютное значение: она не накопленное событие, а решение владельца, и клиент задаёт её
 * целиком.
 *
 * Версии едут телом, но отказ по ним тот же, что у любой команды: решение принято по картине,
 * которой больше нет, и `StaleVersion` уходит наверх как есть.
 */
@Service
class DrugSynchronisation(
    private val access: DrugCommandAccess,
    private val reservationService: ReservationService,
    private val placement: ReservationPlacement,
    private val disposal: DrugDisposal,
    private val journal: IntakeJournal
) {

    @Transactional(propagation = MANDATORY)
    fun apply(syncId: Uuid, drugId: Uuid, userId: Uuid, request: SyncRequest): Drug? {
        val drug = access.content(drugId, userId)
        val consumed = request.consumed
        val intake = Intake(syncId, userId, drug, consumed?.amount, request.reservation?.amount)
        if (journal.alreadyApplied(intake)) return drug

        /*
         * Если sync заводит новую бронь, `ReservationPlacement` сначала удержит корень
         * совместимо. Сделать это после записи упаковки нельзя: глобальный delete уже может
         * держать корень и ждать эту упаковку. Чистый вызов домена заранее отвечает, переживёт
         * ли пачка списание; для уничтоженной пачки бронь, как и прежде, не применяется.
         */
        val survives = consumed?.let {
            drug.consume(Quantity(it.amount, drug.quantity.unit)) != null
        } ?: true
        if (survives) applyReservation(drug, userId, request.reservation)

        // `null` — списание опустошило пачку, и её больше нет вместе с бронями на неё.
        val left = if (consumed != null) {
            disposal.consumeUnderAccess(drug, consumed.amount, consumed.stated)
        } else {
            drug
        }

        journal.rememberAfterCommit(intake)
        return left
    }

    /**
     * Бронь после списания.
     *
     * Если списание опустошило пачку, делать нечего: бронь ушла вместе с ней. Это не ошибка —
     * клиент офлайн и не знал, что пачка кончится.
     */
    private fun applyReservation(drug: Drug?, userId: Uuid, wanted: ReservationPart?) {
        if (drug == null || wanted == null) return

        val snapshot = reservationService.snapshotOn(drug, userId)
        // Версии нет — брони ещё не было, сверяться не с чем: за неизменность отвечает
        // прочитанное только что, и от одновременной записи предикат защищает всё равно.
        val stated = wanted.version ?: snapshot.version

        if (snapshot.mine == null) {
            // Через тот же вход, что и у фасада брони: протокол согласования с составом
            // участников принадлежит заведению брони, а не одному из его вызывающих.
            placement.placeUnderAccess(drug, userId, wanted.amount, stated)
        } else {
            reservationService.changeTo(userId, drug.id, wanted.amount, stated)
        }
    }

    data class SyncRequest(
        val consumed: Consumption?,
        val reservation: ReservationPart?
    )

    /** Списание пишет упаковку, поэтому без предъявленной версии оно не собирается. */
    data class Consumption(val amount: BigDecimal, val stated: Long)

    data class ReservationPart(val amount: BigDecimal, val version: Long?)
}
