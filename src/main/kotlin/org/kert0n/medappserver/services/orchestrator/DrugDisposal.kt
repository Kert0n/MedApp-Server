package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Уничтожение упаковки.
 *
 * Правило одно: **бронь не переживает упаковку** — назначение на выброшенную пачку
 * бессмысленно. Выразить его внутри `Drug` нельзя, упаковка про брони не знает и знать не
 * должна; значит самое внутреннее место для него здесь, где встречаются оба агрегата.
 *
 * Схема то же самое страхует каскадом `reservations_drug_med_kit_fkey`, но правило написано
 * тут, а не там: читающий доменный слой должен увидеть его, не заглядывая в `db/schema.sql`.
 *
 * Оркестратор: домен на входе и на выходе, про клиента не знает.
 */
@Service
class DrugDisposal(
    private val access: DrugCommandAccess,
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val journal: IntakeJournal
) {

    /** Пачку выбросили — назначений на неё больше нет. */
    @Transactional(propagation = MANDATORY)
    fun destroy(drugId: Uuid, userId: Uuid, stated: Long) {
        destroyUnderAccess(access.content(drugId, userId), stated)
    }

    internal fun destroyUnderAccess(drug: Drug, stated: Long) {
        reservationService.dropOnDrug(drug)
        drugService.delete(drug, stated)
    }

    /**
     * Приём, а если он опустошил пачку — то и уничтожение.
     *
     * `null` означает «пачки не стало», а не «не нашли»: недоступная отвергается ещё в
     * списании. Пустой упаковки не бывает — это правило `Drug`, здесь только его последствие
     * для соседнего агрегата.
     *
     * Идентификатор приёма придумывает клиент: повтор с ним ничего не списывает, даже если
     * версия упаковки с тех пор ушла вперёд, — журнал спрашивают раньше предиката версии.
     */
    @Transactional(propagation = MANDATORY)
    fun consume(intakeId: Uuid, drugId: Uuid, userId: Uuid, quantity: BigDecimal, stated: Long): Drug? {
        val drug = access.content(drugId, userId)
        val intake = Intake(intakeId, userId, drug, quantity, reservedTo = null)
        if (journal.alreadyApplied(intake)) return drug

        val left = consumeUnderAccess(drug, quantity, stated)
        journal.rememberAfterCommit(intake)
        return left
    }

    internal fun consumeUnderAccess(drug: Drug, quantity: BigDecimal, stated: Long): Drug? {
        val left = drugService.consume(drug, quantity, stated)
        // Пачка кончилась — списание её не переписывало, а значит и версию не двигало:
        // уничтожение предъявляет ту же самую.
        if (left == null) destroyUnderAccess(drug, stated)
        return left
    }
}
