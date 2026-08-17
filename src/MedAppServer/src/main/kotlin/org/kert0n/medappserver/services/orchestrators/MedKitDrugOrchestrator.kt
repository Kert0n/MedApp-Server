package org.kert0n.medappserver.services.orchestrators

import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сценарии, которые не помещаются ни в один агрегат.
 *
 * Каждый из четырёх ниже трогает и аптечку, и препараты: доступ проверяется по одному
 * агрегату, а меняется другой. Всё, что можно решить внутри одного, сюда не попадает —
 * иначе оркестратор превращается в место, где логика оседает просто потому, что там
 * удобно.
 */
@Service
class MedKitDrugOrchestrator(
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val medKitService: MedKitService,
    private val drugs: DrugStore,
    private val reservations: ReservationStore
) {

    private val logger = LoggerFactory.getLogger(MedKitDrugOrchestrator::class.java)

    /** Доступ к аптечке решает аптечка, заведение препарата — препарат. */
    @Transactional
    fun createDrugInMedKit(medKitId: UUID, request: DrugCreateRequest, userId: UUID): Drug {
        logger.debug("Creating drug {} in medkit {}", request.name, medKitId)
        medKitService.requireAccessible(medKitId, userId)
        return drugService.create(request, medKitId, userId)
    }

    /**
     * Перенос упаковки.
     *
     * Кто увидит пачку после переезда, знает целевая аптечка; брони тех, кто её не видит,
     * исчезают. Три агрегата в одном сценарии — потому он и здесь: упаковка переезжает,
     * аптечка называет состав, брони убираются массово.
     */
    @Transactional
    fun moveDrug(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.requireAccessible(targetMedKitId, userId)
        val moved = drugService.moveTo(drugId, target.id, userId)
        reservations.deleteOfDrugExcept(drugId, target.members)
        return moved
    }

    /**
     * Выход из аптечки.
     *
     * Членство меняет аптечка, а брони выходящего лежат на упаковках — в чужих агрегатах, и их
     * там может быть много. Поэтому брони удаляются одним запросом, а не обходом упаковок:
     * поднимать каждую ради одной строки незачем.
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
     * Удаление аптечки, при желании — с переносом препаратов.
     *
     * Без переноса упаковки уходят каскадом вместе с аптечкой. С переносом действует то же
     * правило, что и при переезде одной пачки, — брони тех, кто целевую аптечку не видит,
     * исчезают, — но выражено оно двумя запросами вместо команды на упаковку: аптечка со ста
     * пачками не должна стоить ста загрузок с блокировкой.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, transferToMedKitId: UUID? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        medKitService.requireAccessible(medKitId, userId)

        if (transferToMedKitId != null) {
            val target = medKitService.requireAccessible(transferToMedKitId, userId)
            // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
            reservations.deleteInMedKitExcept(medKitId, target.members)
            drugs.moveAllToMedKit(medKitId, target.id)
        }

        medKitService.delete(medKitId, userId)
    }

    /** Аптечка вместе с содержимым: сама аптечка знает участников, препараты — себя. */
    @Transactional(readOnly = true)
    fun medKitWithDrugs(medKitId: UUID, userId: UUID): MedKitDTO {
        medKitService.requireAccessible(medKitId, userId)
        return MedKitDTO(
            id = medKitId,
            drugs = drugsWithReservations(drugService.ofMedKit(medKitId)).toSet()
        )
    }

    /**
     * Упаковки вместе с бронями на них.
     *
     * Брони читаются одним запросом на весь набор, сколько бы пачек ни было, и группируются в
     * памяти: дорого обращение в базу, а не объект в памяти.
     */
    private fun drugsWithReservations(packages: List<Drug>): List<DrugDTO> {
        val byDrug = reservationService.onDrugs(packages.map { it.id }).groupBy { it.drugId }
        return packages.map { it.toDto(byDrug[it.id].orEmpty()) }
    }
}
