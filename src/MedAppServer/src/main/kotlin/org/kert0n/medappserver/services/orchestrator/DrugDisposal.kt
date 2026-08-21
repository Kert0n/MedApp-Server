package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.domain.Drug
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
    private val drugService: DrugService,
    private val reservationService: ReservationService
) {

    /** По идентификатору — то же самое плюс чтение пачки. */
    @Transactional(propagation = MANDATORY)
    fun destroy(drugId: UUID, userId: UUID) = destroy(drugService.get(drugId, userId))

    /** Пачку выбросили — назначений на неё больше нет. */
    @Transactional(propagation = MANDATORY)
    fun destroy(drug: Drug) {
        reservationService.dropOnDrug(drug)
        drugService.delete(drug)
    }

    /**
     * Приём, а если он опустошил пачку — то и уничтожение.
     *
     * `null` означает «пачки не стало», а не «не нашли»: недоступная отвергается ещё в
     * списании. Пустой упаковки не бывает — это правило `Drug`, здесь только его последствие
     * для соседнего агрегата.
     */
    @Transactional(propagation = MANDATORY)
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? =
        consume(drugService.get(drugId, userId), quantity)

    @Transactional(propagation = MANDATORY)
    fun consume(drug: Drug, quantity: BigDecimal): Drug? {
        val left = drugService.consume(drug, quantity)
        if (left == null) destroy(drug)
        return left
    }
}
