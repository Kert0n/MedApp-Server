package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Выход участника затрагивает membership и производные картины броней.
 *
 * Оркестратор удерживает корень исключительно от начала до конца: сначала снимает брони и
 * пересчитывает их снимки, затем удаляет одну строку membership. Если участник был последним,
 * сервис аптечки удалит опустевший корень в той же транзакции.
 */
@Service
class MedKitLeaving(
    private val medKitService: MedKitService,
    private val reservationService: ReservationService
) {

    /** По идентификатору — вход сценария: блокировка одновременно доказывает доступ. */
    @Transactional(propagation = MANDATORY)
    fun leave(medKitId: Uuid, userId: Uuid): MedKit? =
        leave(medKitService.lock(setOf(medKitId), userId).single(), userId)

    /** Основная форма для уже исключительно заблокированной аптечки. */
    @Transactional(propagation = MANDATORY)
    fun leave(medKit: MedKit, userId: Uuid): MedKit? {
        reservationService.dropOfMember(medKit, userId)
        return medKitService.leave(medKit, userId)
    }
}
