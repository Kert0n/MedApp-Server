package org.kert0n.medappserver.services.orchestrator

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Списание и приведение брони к величине — в одной транзакции.
 *
 * Оркестратор: домен на входе, домен на выходе. Про клиента здесь не знают ничего — ни про его
 * идентификатор запроса, ни про журнал повторов, ни про то, каким кодом наружу отдаётся отказ.
 * Всё это остаётся у фасада, и намеренно: знание о клиенте стекается в одно место, и следить
 * за толщиной проще там, где оно одно.
 *
 * Здесь только правило взаимодействия двух агрегатов и порядок, в котором оно применяется.
 */
@Service
class DrugSynchronisation(
    private val drugService: DrugService,
    private val reservationService: ReservationService
) {

    private val logger = LoggerFactory.getLogger(DrugSynchronisation::class.java)

    /**
     * `null` — пачка кончилась и уничтожена этим списанием.
     *
     * Тогда бронь не трогается вовсе: применять её некуда, она ушла вместе с упаковкой. Это не
     * ошибка, а законный исход.
     */
    @Transactional
    fun apply(
        drug: Drug,
        userId: UUID,
        consumed: Quantity?,
        reservedTo: Quantity?,
        expectedDrugVersion: Long,
        expectedReservationVersion: Long?
    ): Drug? {
        logger.debug("Synchronising drug {} of user {}", drug.id, userId)

        drug.requireVersion(expectedDrugVersion)
        // Без списания упаковка не записывается, и её версия сама себя не проверит: предусловие
        // осталось бы украшением, а одновременный переезд или смена единицы прошли бы мимо.
        if (consumed == null) drugService.requireUnchanged(drug)

        val left = consumed?.let { drugService.consume(drug.id, it.amount, userId, expectedDrugVersion) }
        if (consumed != null && left == null) return null

        reservedTo?.let { amount ->
            if (expectedReservationVersion == null) {
                reservationService.create(userId, drug.id, amount.amount)
            } else {
                reservationService.changeTo(userId, drug.id, amount.amount, expectedReservationVersion)
            }
        }

        return left ?: drug
    }
}
