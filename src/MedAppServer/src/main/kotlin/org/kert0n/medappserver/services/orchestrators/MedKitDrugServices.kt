package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UserService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class MedKitDrugServices(
    private val drugService: DrugService,
    private val medKitService: MedKitService,
    private val userService: UserService,
    private val logger: Logger = LoggerFactory.getLogger(DrugService::class.java),
    private val medKitRepository: MedKitRepository,
    private val drugRepository: DrugRepository
) {
    @Transactional
    fun createDrugInMedkit(medKitId: UUID, createDTO: DrugCreateRequest, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", createDTO.name, userId)
        val medKit = medKitService.findByIdForUser(medKitId, userId)
        return drugService.create(createDTO, medKit, userId)
    }

    @Transactional
    fun moveDrug(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        val targetMedKit =
            medKitRepository.findByIdAndUsersIdWithUsers(targetMedKitId, userId) ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND
            )
        // Переезд и судьба планов — одно решение агрегата: планы тех, кто целевую аптечку не
        // видит, исчезают вместе с доступом.
        val targetUserIds = targetMedKit.users.map { it.id }.toSet()
        return drugService.moveTo(drugId, targetMedKit, targetUserIds, userId)
    }

 //   fun findAllDrugsInMedkit(medKitId: UUID): List<Drug> = drugService.findAllByMedKit(medKitId)

    @Transactional
    fun removeUserFromMedKit(medKitId: UUID, userId: UUID) {
        logger.debug("Removing user {} from MedKit {}",userId, medKitId)
        val medKit = medKitService.findByIdForUser(medKitId, userId)
        val user = userService.findById(userId)
        val drugs = drugRepository.findAllWithTreatmentPlansByMedKitId(medKitId)
        drugs.forEach { drug -> drugService.revokePlanOf(drug, userId) }
        medKitService.removeUserFromMedKit(medKit, user)
    }

    @Transactional
    fun delete(medKitId: UUID, userId: UUID, transferToMedKitId: UUID? = null) {
        val medKit = medKitRepository.findByIdAndUserIdForDeletion(medKitId, userId) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND, "Cant find deletion target"
        )

        if (transferToMedKitId != null) {
            val targetMedKit = medKitService.findByIdForUser(transferToMedKitId, userId)

            // Get the IDs of everyone who has access to the new MedKit
            val usersWithAccess = targetMedKit.users.map { it.id }.toSet()
            medKit.drugs.toList().forEach { drug ->
                drugService.moveTo(drug.id, targetMedKit, usersWithAccess, userId)
                targetMedKit.drugs.add(drug)
            }
            // Sync
            medKit.drugs.clear()
        }
        // Sync
        medKit.users.forEach { user ->
            user.medKits.remove(medKit)
        }
        // Sync
        medKitRepository.delete(medKit)
    }

    @Transactional(readOnly = true)
    /** Содержимое аптечки читается проекцией: суммы планов считает база. */
    fun toMedKitDTO(medKit: MedKit): MedKitDTO = MedKitDTO(
        id = medKit.id,
        drugs = drugService.viewsOfMedKit(medKit.id).map { it.toDto() }.toSet()
    )
}