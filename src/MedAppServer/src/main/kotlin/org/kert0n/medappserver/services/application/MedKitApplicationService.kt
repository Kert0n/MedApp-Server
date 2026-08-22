package org.kert0n.medappserver.services.application

import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.statedVersion
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toSnapshots
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
    fun create(userId: Uuid): MedKitCreatedDTO = MedKitCreatedDTO(medKitService.create(userId).id)

    /** Аптечка вместе с содержимым: сама аптечка знает участников, упаковки — себя. */
    @Transactional(readOnly = true)
    fun read(medKitId: Uuid, userId: Uuid): MedKitDTO {
        val medKit = medKitService.get(medKitId, userId)
        val packages = drugService.ofMedKit(medKitId, userId)
        val reservations = reservationService.onDrugs(packages, userId)
        return medKit.toDto(packages.toSnapshots(reservations).toSet())
    }

    /** Список аптечек со счётчиками — два чтения на весь ответ, сколько бы их ни было. */
    @Transactional(readOnly = true)
    fun summaries(userId: Uuid): Set<MedKitSummaryDTO> =
        medKitService.allOfUser(userId).toSummaryDto(drugService.allOf(userId))

    @Transactional
    fun invite(medKitId: Uuid, userId: Uuid): InvitationDTO =
        InvitationDTO(medKitService.invite(medKitId, userId))

    @Transactional
    fun joinByInvitation(key: String, userId: Uuid): MedKitDTO {
        val joined = medKitService.joinByInvitation(key, userId)
        return read(joined.id, userId)
    }

    /**
     * Выход из аптечки.
     *
     * Правило: **человек ушёл от хранилища — его назначения на пачки внутри него сняты.**
     *
     * Снимать их отдельным запросом больше не нужно и нечего: бронь ссылается на членство, и
     * вместе со строкой членства уходит по каскаду. Это не «уборка, которая может не
     * отработать», а то же самое правило, выраженное ключом — см. `AccessKeysTest`.
     *
     * Не путать с переездом коробки: там человек никуда не девался, и назначение остаётся,
     * если он допущен к новому месту. Там ключ правило выразить не может — см. `DrugRelocation`.
     */
    @Transactional
    fun leave(medKitId: Uuid, version: Long?, userId: Uuid) {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        medKitService.leave(medKitId, userId, statedVersion(version))
    }

    /**
     * Удаление аптечки, при желании — с переносом упаковок.
     *
     * Без переноса содержимое уходит каскадом. С переносом действует то же правило, что при
     * переезде одной пачки, поэтому и живёт оно в одном месте на оба случая.
     */
    @Transactional
    fun delete(medKitId: Uuid, version: Long?, userId: Uuid, transferToMedKitId: Uuid? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        transferToMedKitId?.let { relocation.moveAll(medKitId, it, userId) }
        medKitService.delete(medKitId, userId, statedVersion(version))
    }
}
