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
    private val reservationService: ReservationService
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
     * переезде одной пачки, но двумя запросами: аптечка со ста пачками не должна стоить ста
     * загрузок.
     *
     * Состав целевой аптечки требуется дважды, и это не перестраховка. **Замерено:** массовый
     * `UPDATE` с `clearAutomatically` очищает persistence context и уносит вместе с ним
     * зарегистрированную проверку версии — снял из сценария один этот запрос, и та же гонка
     * стала отвергаться. Поэтому состав перепроверяется после него, уже по свежему чтению;
     * держится это на том, что `requireUnchanged` сравнивает версию со снимком явно.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, expectedVersion: Long, transferToMedKitId: UUID? = null) {
        logger.debug("Deleting medkit {} (transfer to {})", medKitId, transferToMedKitId)
        medKitService.requireAccessibleAt(medKitId, userId, expectedVersion)

        if (transferToMedKitId != null) {
            val target = medKitService.requireAccessible(transferToMedKitId, userId)
            // Тот же состав, что и при переносе одной пачки, — и то же требование к нему.
            medKitService.requireUnchanged(target)
            // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
            reservationService.dropInMedKitExcept(medKitId, target.members)
            drugService.moveAllToMedKit(medKitId, target.id)
            medKitService.requireUnchanged(target)
        }

        medKitService.delete(medKitId, userId, expectedVersion)
    }
}
