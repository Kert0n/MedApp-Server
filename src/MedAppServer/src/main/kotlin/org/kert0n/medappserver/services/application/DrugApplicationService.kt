package org.kert0n.medappserver.services.application

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrator.DrugDisposal
import org.kert0n.medappserver.services.orchestrator.DrugRelocation
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
    private val disposal: DrugDisposal
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
        return drug.toDto(reservationService.onDrugs(listOf(drug.id), userId))
    }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /** Доступ решает аптечка, заведение упаковки — упаковка. */
    @Transactional
    fun createInMedKit(medKitId: UUID, request: DrugCreateRequest, userId: UUID): DrugDTO {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        medKitService.require(medKitId, userId)
        return drugService.create(request.toCommand(), medKitId, userId).toDto(emptyList())
    }

    @Transactional
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID): DrugDTO {
        drugService.update(drugId, request.toCommand(), userId)
        return read(drugId, userId)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) = disposal.destroy(drugId, userId)

    /** `null` — приём опустошил пачку, и она уничтожена: отдавать нечего. */
    @Transactional
    fun recordIntake(drugId: UUID, quantity: BigDecimal, userId: UUID): DrugDTO? {
        disposal.consume(drugId, quantity, userId) ?: return null
        return read(drugId, userId)
    }

    @Transactional
    fun moveToMedKit(drugId: UUID, targetMedKitId: UUID, userId: UUID): DrugDTO {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.require(targetMedKitId, userId)
        relocation.moveOne(drugId, target, userId)
        return read(drugId, userId)
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
