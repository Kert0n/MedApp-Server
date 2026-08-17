package org.kert0n.medappserver.services.orchestrators

import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toSummaryDto
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сценарии и чтения, которые не помещаются ни в один агрегат.
 *
 * Их трое: аптечка, упаковка, бронь. Доступ проверяется по одному, меняется другой, а в ответе
 * нужны данные третьего. Всё, что решается внутри одного агрегата, сюда не попадает — иначе
 * оркестратор станет местом, где логика оседает просто потому, что здесь удобно.
 */
@Service
class MedKitDrugOrchestrator(
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val medKitService: MedKitService,
    private val drugs: DrugStore,
    private val reservations: ReservationStore,
    private val medKits: MedKitStore
) {

    private val logger = LoggerFactory.getLogger(MedKitDrugOrchestrator::class.java)

    /** Доступ решает аптечка, заведение упаковки — упаковка. */
    @Transactional
    fun createDrugInMedKit(medKitId: UUID, request: DrugCreateRequest, userId: UUID): Drug {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        medKitService.requireAccessible(medKitId, userId)
        return drugService.create(request, medKitId, userId)
    }

    /**
     * Перенос упаковки.
     *
     * Три агрегата в одном сценарии: пачка переезжает, аптечка называет состав, брони тех, кто
     * её больше не видит, убираются массово.
     *
     * Состав аптечки здесь — основание решения, а не то, что меняется: `requireUnchanged`
     * требует, чтобы он дожил до коммита. Иначе вышедший в этот момент участник сохранил бы
     * бронь на пачку, которую больше не видит.
     */
    @Transactional
    fun moveDrug(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.requireAccessible(targetMedKitId, userId)
        medKits.requireUnchanged(target)
        val moved = drugService.moveTo(drugId, target.id, userId)
        reservations.deleteOfDrugExcept(drugId, target.members)
        return moved
    }

    /**
     * Выход из аптечки.
     *
     * Брони выходящего лежат на упаковках, и их может быть много: удаляются одним запросом, а
     * не обходом пачек.
     */
    @Transactional
    fun leaveMedKit(medKitId: UUID, userId: UUID) {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = medKitService.leave(medKitId, userId)
        // Аптечки не стало — брони ушли вместе с упаковками по каскаду.
        if (left != null) {
            reservations.deleteOfUserInMedKit(userId, medKitId)
        }
    }

    /**
     * Удаление аптечки, при желании — с переносом упаковок.
     *
     * Без переноса содержимое уходит каскадом. С переносом действует то же правило, что при
     * переезде одной пачки, но двумя запросами: аптечка со ста пачками не должна стоить ста
     * загрузок.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, transferToMedKitId: UUID? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        medKitService.requireAccessible(medKitId, userId)

        if (transferToMedKitId != null) {
            val target = medKitService.requireAccessible(transferToMedKitId, userId)
            // Тот же состав, что и при переносе одной пачки, — и то же требование к нему.
            medKits.requireUnchanged(target)
            // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
            reservations.deleteInMedKitExcept(medKitId, target.members)
            drugs.moveAllToMedKit(medKitId, target.id)
        }

        medKitService.delete(medKitId, userId)
    }

    /**
     * Упаковка вместе с тем, сколько на неё заявлено бронями.
     *
     * Сама упаковка про брони не знает, поэтому чтений два — и они здесь, а не у вызывающего:
     * контроллеру нужен готовый ответ, а не два агрегата.
     */
    @Transactional(readOnly = true)
    fun drug(drugId: UUID, userId: UUID): DrugDTO =
        drugsWithReservations(listOf(drugService.require(drugId, userId))).single()

    /** Все доступные упаковки с бронями на них — для снимка пользователя. */
    @Transactional(readOnly = true)
    fun drugsAccessibleTo(userId: UUID): List<DrugDTO> =
        drugsWithReservations(drugService.accessibleTo(userId))

    /** Аптечка вместе с содержимым: сама аптечка знает участников, упаковки — себя. */
    @Transactional(readOnly = true)
    fun medKitWithDrugs(medKitId: UUID, userId: UUID): MedKitDTO {
        medKitService.requireAccessible(medKitId, userId)
        return MedKitDTO(
            id = medKitId,
            drugs = drugsWithReservations(drugService.ofMedKit(medKitId)).toSet()
        )
    }

    /**
     * Список аптечек со счётчиками — два чтения на весь ответ, сколько бы их ни было.
     *
     * Отдельного типа под счётчики не нужно: оба набора здесь и так на руках.
     */
    @Transactional(readOnly = true)
    fun medKitSummaries(userId: UUID): Set<MedKitSummaryDTO> {
        val packagesPerMedKit = drugService.accessibleTo(userId).groupingBy { it.medKitId }.eachCount()
        return medKitService.allOfUser(userId)
            .map { it.toSummaryDto(packagesPerMedKit[it.id] ?: 0) }
            .toSet()
    }

    /** Брони читаются одним запросом на весь набор и группируются в памяти. */
    private fun drugsWithReservations(packages: List<Drug>): List<DrugDTO> {
        val byDrug = reservationService.onDrugs(packages.map { it.id }).groupBy { it.drugId }
        return packages.map { it.toDto(byDrug[it.id].orEmpty()) }
    }
}
