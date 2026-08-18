package org.kert0n.medappserver.services.application

import java.util.UUID
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toSummaryDto
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrator.DrugRelocation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Всё, что клиент делает с аптечкой и своим членством в ней.
 *
 * Содержимое собирает сервис упаковок: `DrugDTO` — его ответственность, и звать его отсюда
 * дешевле и честнее, чем повторять сборку. Обратной зависимости нет, поэтому и цикла нет.
 */
@Service
class MedKitApplicationService(
    private val medKitService: MedKitService,
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val relocation: DrugRelocation
) {

    private val logger = LoggerFactory.getLogger(MedKitApplicationService::class.java)

    @Transactional
    fun create(userId: UUID): MedKitCreatedDTO = MedKitCreatedDTO(medKitService.create(userId).id)

    /** Аптечка вместе с содержимым: сама аптечка знает участников, упаковки — себя. */
    @Transactional(readOnly = true)
    fun read(medKitId: UUID, userId: UUID): MedKitDTO {
        val medKit = medKitService.requireAccessible(medKitId, userId)
        val packages = drugService.ofMedKit(medKitId)
        return medKit.toDto(packages.toDto(reservationService.onDrugs(packages.map { it.id })).toSet())
    }

    /** Список аптечек со счётчиками — два чтения на весь ответ, сколько бы их ни было. */
    @Transactional(readOnly = true)
    fun summaries(userId: UUID): Set<MedKitSummaryDTO> =
        medKitService.allOfUser(userId).toSummaryDto(drugService.accessibleTo(userId))

    @Transactional
    fun invite(medKitId: UUID, userId: UUID): InvitationDTO =
        InvitationDTO(medKitService.invite(medKitId, userId))

    @Transactional
    fun joinByInvitation(key: String, userId: UUID): MedKitDTO {
        val joined = medKitService.joinByInvitation(key, userId)
        return read(joined.id, userId)
    }

    /**
     * Выход из аптечки.
     *
     * Брони выходящего лежат на упаковках, и их может быть много: удаляются одним запросом, а
     * не обходом пачек.
     */
    @Transactional
    fun leave(medKitId: UUID, userId: UUID, expectedVersion: Long) {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = medKitService.leave(medKitId, userId, expectedVersion)
        // Аптечки не стало — брони ушли вместе с упаковками по каскаду.
        if (left != null) {
            reservationService.dropOfUserInMedKit(userId, medKitId)
        }
    }

    /**
     * Удаление аптечки, при желании — с переносом упаковок.
     *
     * Без переноса содержимое уходит каскадом. С переносом действует то же правило, что при
     * переезде одной пачки, поэтому и живёт оно в одном месте на оба случая.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, expectedVersion: Long, transferToMedKitId: UUID? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        medKitService.requireAccessibleAt(medKitId, userId, expectedVersion)

        transferToMedKitId?.let {
            relocation.moveAll(medKitId, medKitService.requireAccessible(it, userId))
        }

        medKitService.delete(medKitId, userId, expectedVersion)
    }
}
