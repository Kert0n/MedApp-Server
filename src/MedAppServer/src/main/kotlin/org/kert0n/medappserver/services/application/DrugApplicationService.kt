package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.toSnapshot
import org.kert0n.medappserver.domain.ReservationSnapshot
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrator.DrugDisposal
import org.kert0n.medappserver.services.orchestrator.DrugPlacement
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
    private val relocation: DrugRelocation,
    private val disposal: DrugDisposal,
    private val placement: DrugPlacement,
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
    fun read(drugId: Uuid, userId: Uuid): DrugSnapshotDTO {
        val drug = drugService.get(drugId, userId)
        return drug.toSnapshot(reservationService.onDrugs(listOf(drug), userId).getValue(drug.id))
    }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /** Доступ решает аптечка, заведение упаковки — упаковка. */
    @Transactional
    fun createInMedKit(medKitId: Uuid, request: DrugCreateRequest, userId: Uuid): DrugSnapshotDTO {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        // Свежая упаковка: броней на неё быть не может, читать нечего.
        val created = placement.place(request.toCommand(), medKitId, userId)
        return created.toSnapshot(ReservationSnapshot.empty(created.id, version = 0))
    }

    @Transactional
    fun update(drugId: Uuid, request: DrugPatchRequest, userId: Uuid): DrugSnapshotDTO {
        val drug = drugService.get(drugId, userId)
        val updated = drugService.update(drug, request.toCommand(), statedVersion(request.version))
        return updated.toSnapshot(reservationService.onDrugs(listOf(updated), userId).getValue(updated.id))
    }

    @Transactional
    fun delete(drugId: Uuid, version: Long?, userId: Uuid) =
        disposal.destroy(drugService.get(drugId, userId), statedVersion(version))

    /** `null` — приём опустошил пачку, и она уничтожена: отдавать нечего. */
    @Transactional
    fun recordIntake(drugId: Uuid, quantity: BigDecimal, version: Long?, userId: Uuid): DrugSnapshotDTO? {
        val left = disposal.consume(drugService.get(drugId, userId), quantity, statedVersion(version))
            ?: return null
        return left.toSnapshot(reservationService.onDrugs(listOf(left), userId).getValue(left.id))
    }

    @Transactional
    fun moveToMedKit(drugId: Uuid, targetMedKitId: Uuid, version: Long?, userId: Uuid): DrugSnapshotDTO {
        val drug = drugService.get(drugId, userId)
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val moved = relocation.moveOne(drug, medKitService.get(targetMedKitId, userId), statedVersion(version))
        return moved.toSnapshot(reservationService.onDrugs(listOf(moved), userId).getValue(moved.id))
    }

    /**
     * Форма запроса переводится в команду здесь: фасад — единственный, кто знает обе стороны.
     *
     * Ниже про контракт не знают вовсе, поэтому его правка не доходит до правил.
     */

    /**
     * Синхронизация: съеденное и бронь одной транзакцией.
     *
     * `null` в ответе — пачка кончилась этим списанием и уничтожена; часть про бронь тогда
     * пустая, потому что бронь ушла вместе с упаковкой.
     */
    @Transactional
    fun synchronise(drugId: Uuid, syncId: Uuid, request: DrugSyncRequest, userId: Uuid): DrugSnapshotDTO? {
        logger.debug("Synchronising drug {} by user {}, sync {}", drugId, userId, syncId)
        // Упаковка читается здесь, скоуплено: дальше она идёт агрегатом, а не идентификатором,
        // и доказывает доступ сама.
        val left = synchronisation.apply(
            syncId, drugService.get(drugId, userId), userId,
            DrugSynchronisation.SyncRequest(
                consumed = request.consumed,
                drugVersion = request.drugVersion,
                reservation = request.reservation?.let {
                    DrugSynchronisation.ReservationPart(it.amount, it.version)
                }
            )
        ) ?: return null
        return left.toSnapshot(reservationService.onDrugs(listOf(left), userId).getValue(left.id))
    }

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
