package org.kert0n.medappserver.services.orchestrators

import java.util.UUID
import org.kert0n.medappserver.api.SyncRequest
import org.kert0n.medappserver.api.SyncResultDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.domain.ConflictingSync
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.IntakeJournal
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.StaleAggregateVersion
import org.kert0n.medappserver.domain.StaleSyncVersion
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Синхронизация одной пачки: приём и бронь в одной транзакции.
 *
 * Порознь эти две команды дают разрыв, который виден человеку: приходит списание, кто-то читает
 * «лекарства мало», и только потом доезжает уменьшение брони. Порядок двух запросов клиент не
 * контролирует, поэтому запрос один — и он либо применяется целиком, либо не применяется вовсе.
 *
 * Повтор после обрыва связи не должен списывать второй раз, а отличить его от новой команды
 * можно только по метке клиента: `syncId`. Журнал помнит выполненный запрос и отвечает на
 * повтор текущим состоянием, а на тот же идентификатор с другим содержимым — отказом.
 */
@Service
class DrugSyncOrchestrator(
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val medKitDrugOrchestrator: MedKitDrugOrchestrator,
    private val journal: IntakeJournal
) {

    private val logger = LoggerFactory.getLogger(DrugSyncOrchestrator::class.java)

    /**
     * `null` — пачка кончилась и уничтожена этой синхронизацией; бронь ушла вместе с ней.
     *
     * Предусловия проверяются только у новой команды. У повтора они заведомо устарели — их
     * подвинул он сам, — и требовать их значило бы отказывать клиенту за то, что его первый
     * запрос дошёл.
     */
    @Transactional
    fun synchronise(syncId: UUID, drugId: UUID, request: SyncRequest, userId: UUID): SyncResultDTO? {
        logger.debug("PUT sync {} of drug {} by user {}", syncId, drugId, userId)

        // Доступ проверяется первым и для повтора тоже: журнал не место для чужих пачек.
        val drug = drugService.require(drugId, userId)
        val requested = Intake(
            id = syncId,
            drugId = drugId,
            userId = userId,
            consumed = request.consumed?.let { Quantity(it, drug.quantity.unit) },
            reservedTo = request.reservation?.let { Quantity(it.amount, drug.quantity.unit) }
        )

        journal.find(syncId)?.let { previous ->
            if (!requested.isRepeatOf(previous)) throw ConflictingSync()
            logger.debug("Sync {} is a repeat, answering with the current state", syncId)
            return currentState(drugId, userId)
        }

        // Версии синхронизации приехали телом, а не в `If-Match`, поэтому их несовпадение это
        // конфликт состояния, а не невыполненное предусловие запроса: 409, а не 412.
        asSyncConflict {
            drug.requireVersion(request.drugVersion)
            // Без приёма упаковка не записывается, и её версия сама себя не проверит: сравнение
            // выше осталось бы украшением, а переезд или смена единицы прошли бы мимо.
            if (request.consumed == null) drugService.requireUnchanged(drug)
        }

        val left = asSyncConflict {
            request.consumed?.let { drugService.consume(drugId, it, userId, request.drugVersion) }
        }

        // Пачки не стало — брони на ней тоже: правку несуществующей брони применять некуда.
        if (request.consumed != null && left == null) {
            journal.record(requested)
            return null
        }

        asSyncConflict {
            request.reservation?.let { wanted ->
                if (wanted.version == null) {
                    medKitDrugOrchestrator.createReservation(userId, drugId, wanted.amount)
                } else {
                    reservationService.changeTo(userId, drugId, wanted.amount, wanted.version)
                }
            }
        }

        journal.record(requested)
        return currentState(drugId, userId)
    }

    /**
     * Устаревшая версия здесь не невыполненное предусловие, а конфликт: её предъявили телом.
     *
     * Перевод стоит в одном месте — там, где известен источник версии. Сервисы про это знать не
     * должны: для них устаревшая версия остаётся устаревшей версией.
     */
    private fun <T> asSyncConflict(block: () -> T): T =
        try {
            block()
        } catch (stale: StaleAggregateVersion) {
            throw StaleSyncVersion().apply { initCause(stale) }
        }

    /** Оба ресурса перечитываются после записи: до коммита новых версий никто не знает. */
    private fun currentState(drugId: UUID, userId: UUID): SyncResultDTO? {
        val drug = drugService.find(drugId, userId) ?: return null
        return SyncResultDTO(
            drug = medKitDrugOrchestrator.drug(drug.id, userId),
            reservation = reservationService.find(userId, drugId)?.toDto()
        )
    }
}
