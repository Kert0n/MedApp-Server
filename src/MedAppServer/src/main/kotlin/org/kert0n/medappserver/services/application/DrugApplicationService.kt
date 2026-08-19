package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.SyncRequest
import org.kert0n.medappserver.api.SyncResultDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.StaleAggregateVersion
import org.kert0n.medappserver.domain.StaleSyncVersion
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.IntakeService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrator.DrugRelocation
import org.kert0n.medappserver.services.orchestrator.DrugSynchronisation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Всё, что клиент делает с упаковкой.
 *
 * Прикладной слой разрезан по ресурсу, а не по числу задетых агрегатов: снаружи не должно быть
 * видно, что «прочитать пачку» трогает два агрегата, а «удалить» один. Контроллеру нужен один
 * собеседник на ресурс, иначе выбор между ними становится знанием, которое обязан держать
 * HTTP-слой.
 *
 * Ниже — сервисы агрегатов; до хранилищ отсюда не дотянуться, и это намеренно.
 */
@Service
class DrugApplicationService(
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val medKitService: MedKitService,
    private val intakes: IntakeService,
    private val relocation: DrugRelocation,
    private val synchronisation: DrugSynchronisation
) {

    private val logger = LoggerFactory.getLogger(DrugApplicationService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /**
     * Упаковка вместе с тем, сколько на неё заявлено бронями.
     *
     * Сама упаковка про брони не знает, поэтому чтений два — и они здесь, а не у вызывающего:
     * контроллеру нужен готовый ответ, а не два агрегата.
     */
    @Transactional(readOnly = true)
    fun read(drugId: UUID, userId: UUID): DrugDTO {
        val drug = drugService.require(drugId, userId)
        return drug.toDto(reservationService.onDrugs(listOf(drug.id)))
    }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /**
     * Доступ решает аптечка, заведение упаковки — упаковка.
     *
     * Состав удерживается до коммита: между проверкой доступа и записью вызывающий успевает из
     * аптечки выйти, и пачка легла бы в чужую.
     */
    @Transactional
    fun createInMedKit(medKitId: UUID, request: DrugCreateRequest, userId: UUID): DrugDTO {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        medKitService.requireUnchanged(medKitService.requireAccessible(medKitId, userId))
        return drugService.create(request.toCommand(), medKitId, userId).toDto(emptyList())
    }

    @Transactional
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID, expectedVersion: Long): DrugDTO {
        drugService.update(drugId, request.toCommand(), userId, expectedVersion)
        return read(drugId, userId)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID, expectedVersion: Long) =
        drugService.delete(drugId, userId, expectedVersion)

    /** `null` — приём опустошил пачку, и она уничтожена: отдавать нечего. */
    @Transactional
    fun recordIntake(drugId: UUID, quantity: BigDecimal, userId: UUID, expectedVersion: Long): DrugDTO? {
        drugService.consume(drugId, quantity, userId, expectedVersion) ?: return null
        return read(drugId, userId)
    }

    @Transactional
    fun moveToMedKit(drugId: UUID, targetMedKitId: UUID, userId: UUID, expectedVersion: Long): DrugDTO {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.requireAccessible(targetMedKitId, userId)
        relocation.moveOne(drugId, target, userId, expectedVersion)
        return read(drugId, userId)
    }

    // ── Синхронизация ────────────────────────────────────────────────────────────

    /**
     * Приём и бронь одной пачки в одной транзакции.
     *
     * Порознь эти две команды дают разрыв, который виден человеку: приходит списание, кто-то
     * читает «лекарства мало», и только потом доезжает уменьшение брони. Порядок двух запросов
     * клиент не контролирует, поэтому запрос один — и он либо применяется целиком, либо нет.
     *
     * Повтор после обрыва связи не должен списывать второй раз, а отличить его от новой команды
     * можно только по метке клиента: `syncId`. Журнал помнит выполненный запрос и отвечает на
     * повтор текущим состоянием, а на тот же идентификатор с другим содержимым — отказом.
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

        if (intakes.alreadyApplied(requested)) return currentState(drugId, userId)

        // Версии синхронизации приехали телом, а не в `If-Match`, поэтому их несовпадение это
        // конфликт состояния, а не невыполненное предусловие запроса: 409, а не 412. Различие
        // контрактное, поэтому перевод стоит здесь, а не там, где отказ рождается.
        val left = asSyncConflict {
            synchronisation.apply(
                drug = drug,
                userId = userId,
                consumed = requested.consumed,
                reservedTo = requested.reservedTo,
                expectedDrugVersion = request.drugVersion,
                expectedReservationVersion = request.reservation?.version
            )
        }

        intakes.record(requested)
        // Пачки не стало — отдавать нечего.
        return if (left == null) null else currentState(drugId, userId)
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
            drug = read(drug.id, userId),
            reservation = reservationService.find(userId, drugId)?.toDto()
        )
    }

    /**
     * Форма запроса переводится в команду здесь: фасад — единственный, кто знает обе стороны.
     *
     * Ниже про контракт не знают вовсе, поэтому его правка не доходит до правил.
     */
    private fun DrugCreateRequest.toCommand() = NewDrug(
        name = name,
        quantity = quantity,
        quantityUnitId = quantityUnitId,
        formTypeId = formTypeId,
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )

    private fun DrugPatchRequest.toCommand() = DrugEdit(
        name = name,
        quantity = quantity,
        quantityUnitId = quantityUnitId,
        formTypeId = formTypeId,
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}
