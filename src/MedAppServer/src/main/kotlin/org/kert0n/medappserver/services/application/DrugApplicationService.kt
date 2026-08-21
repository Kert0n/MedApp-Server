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
        val drug = drugService.get(drugId, userId)
        return drug.toDto(reservationService.onDrugs(listOf(drug.id), userId))
    }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /** Доступ решает аптечка, заведение упаковки — упаковка. */
    @Transactional
    fun createInMedKit(medKitId: UUID, request: DrugCreateRequest, userId: UUID): DrugDTO {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        // Аптечка читается, а не подразумевается: прочитанный агрегат и есть право завести в
        // нём упаковку, и он же аргумент команды.
        val medKit = medKitService.get(medKitId, userId)
        return drugService.create(request.toCommand(), medKit).toDto(emptyList())
    }

    @Transactional
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID): DrugDTO {
        val updated = drugService.update(drugService.get(drugId, userId), request.toCommand())
        return updated.toDto(reservationService.onDrugs(listOf(updated.id), userId))
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) = disposal.destroy(drugService.get(drugId, userId))

    /** `null` — приём опустошил пачку, и она уничтожена: отдавать нечего. */
    @Transactional
    fun recordIntake(drugId: UUID, quantity: BigDecimal, userId: UUID): DrugDTO? {
        val left = disposal.consume(drugService.get(drugId, userId), quantity) ?: return null
        return left.toDto(reservationService.onDrugs(listOf(left.id), userId))
    }

    @Transactional
    fun moveToMedKit(drugId: UUID, targetMedKitId: UUID, userId: UUID): DrugDTO {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.get(targetMedKitId, userId)
        val moved = relocation.moveOne(drugService.get(drugId, userId), target)
        return moved.toDto(reservationService.onDrugs(listOf(moved.id), userId))
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
