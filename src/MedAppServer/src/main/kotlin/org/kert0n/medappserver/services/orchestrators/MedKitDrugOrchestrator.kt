package org.kert0n.medappserver.services.orchestrators

import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
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
    private val medKitService: MedKitService,
    private val drugs: DrugStore
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
     * Перенос препарата.
     *
     * Кто увидит препарат после переезда, знает целевая аптечка; что при этом станет с
     * планами — препарат. Поэтому список участников берётся у одного агрегата и передаётся
     * другому.
     */
    @Transactional
    fun moveDrug(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val target = medKitService.requireAccessible(targetMedKitId, userId)
        return drugService.moveTo(drugId, target.id, target.members, userId)
    }

    /**
     * Выход из аптечки.
     *
     * Членство меняет аптечка, а планы выходящего лежат в препаратах — в чужих агрегатах, и
     * их там может быть много. Поэтому планы удаляются одним запросом, а не обходом
     * препаратов: поднимать каждый ради одной строки незачем.
     */
    @Transactional
    fun leaveMedKit(medKitId: UUID, userId: UUID) {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = medKitService.removeUserFromMedKit(medKitId, userId)
        // Аптечки не стало — планы ушли вместе с препаратами по каскаду.
        if (left != null) {
            drugs.deletePlansOfUserInMedKit(userId, medKitId)
        }
    }

    /**
     * Удаление аптечки, при желании — с переносом препаратов.
     *
     * Без переноса препараты уходят каскадом вместе с аптечкой. С переносом каждый препарат
     * переезжает своим правилом: планы тех, кто целевую аптечку не видит, исчезают.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, transferToMedKitId: UUID? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        medKitService.requireAccessible(medKitId, userId)

        if (transferToMedKitId != null) {
            val target = medKitService.requireAccessible(transferToMedKitId, userId)
            drugService.ofMedKit(medKitId).forEach { drug ->
                drugService.moveTo(drug.id, target.id, target.members, userId)
            }
        }

        medKitService.delete(medKitId)
    }

    /** Аптечка вместе с содержимым: сама аптечка знает участников, препараты — себя. */
    @Transactional(readOnly = true)
    fun medKitWithDrugs(medKitId: UUID, userId: UUID): MedKitDTO {
        medKitService.requireAccessible(medKitId, userId)
        return MedKitDTO(
            id = medKitId,
            drugs = drugService.ofMedKit(medKitId).map { it.toDto() }.toSet()
        )
    }
}
