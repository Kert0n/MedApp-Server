package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Заведение брони на упаковку.
 *
 * Правило взаимодействия: **бронь заводится в единице величины той пачки, на которую заведена,
 * и только пока пачка видна вызывающему.** Обе половины даёт одно чтение упаковки — оно же и
 * есть проверка доступа.
 *
 * Читает чужой агрегат здесь, а не в `ReservationService`: сервису брони знать про упаковку не
 * положено, иначе он начнёт нести правила соседа. Своё правило — «одна бронь на пару человек и
 * пачка» — остаётся у него, и сюда не переезжает.
 *
 * Оркестратор: домен на входе и на выходе, про клиента не знает.
 */
@Service
class ReservationPlacement(
    private val drugService: DrugService,
    private val reservationService: ReservationService
) {

    /** Основная форма: пачка уже прочитана, значит доступ к ней доказан. */
    @Transactional(propagation = MANDATORY)
    fun place(drug: Drug, userId: Uuid, amount: BigDecimal, stated: Long): Reservation =
        reservationService.create(drug, userId, amount, stated)

    /** По идентификатору — то же самое плюс чтение пачки, оно же проверка доступа. */
    @Transactional(propagation = MANDATORY)
    fun place(drugId: Uuid, userId: Uuid, amount: BigDecimal, stated: Long): Reservation =
        place(drugService.get(drugId, userId), userId, amount, stated)
}
