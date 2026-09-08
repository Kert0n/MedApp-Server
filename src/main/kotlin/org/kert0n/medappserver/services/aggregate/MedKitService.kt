package org.kert0n.medappserver.services.aggregate

import kotlin.uuid.Uuid
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Аптечка: чтения и отдельные операции над строкой membership.
 *
 * Доступ здесь не удерживается: это дело сценария, который командой владеет. Поэтому операции,
 * меняющие состав участников, объявлены `internal` — снаружи пакета их вызвать нельзя, а внутри
 * их зовут только `MedKitJoining`, `MedKitLeaving` и `MedKitDeletion`, уже под блокировкой корня.
 */
@Service
class MedKitService(private val medKits: MedKitStore) {

    private val logger = LoggerFactory.getLogger(MedKitService::class.java)

    @Transactional(propagation = MANDATORY)
    fun create(userId: Uuid): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit()
        medKits.insert(medKit, userId)
        return medKit
    }

    /**
     * Аптечка вызывающего. Недоступная и несуществующая неотличимы намеренно.
     *
     * Единственный способ получить `MedKit`: чтение и есть проверка доступа.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun get(medKitId: Uuid, userId: Uuid): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        return medKits.find(medKitId, userId) ?: throw NotAMember()
    }

    /** Все аптечки участника со счётчиками — одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun allOfUser(userId: Uuid): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findAllOfUser(userId)
    }

    /** Вступление: строка membership и ничего больше — корень уже держит сценарий. */
    @Transactional(propagation = MANDATORY)
    internal fun addMembership(medKitId: Uuid, userId: Uuid) = medKits.insertMembership(medKitId, userId)

    /**
     * `true` — вышел последний, и аптечка удалена вместе с содержимым.
     *
     * Брони выходящего лежат в чужом агрегате: их убирает оркестратор.
     */
    @Transactional(propagation = MANDATORY)
    internal fun removeMembership(medKitId: Uuid, userId: Uuid): Boolean {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        medKits.deleteMembership(medKitId, userId)
        if (!medKits.hasMembers(medKitId)) {
            medKits.delete(medKitId)
            return true
        }
        return false
    }

    /** Глобальное удаление: содержимое и membership уносит каскад. */
    @Transactional(propagation = MANDATORY)
    internal fun deleteRoot(medKitId: Uuid) {
        medKits.delete(medKitId)
    }
}
