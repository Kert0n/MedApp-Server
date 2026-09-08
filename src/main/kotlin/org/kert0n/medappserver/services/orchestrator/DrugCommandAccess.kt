package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitAccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/** Загружает упаковку так, чтобы возвращённое состояние было готово к команде. */
@Service
class DrugCommandAccess(
    private val drugs: DrugService,
    private val medKits: MedKitAccessService
) {

    @Transactional(propagation = MANDATORY)
    fun content(drugId: Uuid, userId: Uuid): Drug {
        val observed = drugs.get(drugId, userId)
        medKits.holdContentAccess(setOf(observed.medKitId), userId)
        return reloadInObservedRoot(observed, userId)
    }

    @Transactional(propagation = MANDATORY)
    fun lifecycle(drugId: Uuid, targetMedKitId: Uuid, userId: Uuid): Drug {
        val observed = drugs.get(drugId, userId)
        medKits.holdLifecycleAccess(setOf(observed.medKitId, targetMedKitId), userId)
        return reloadInObservedRoot(observed, userId)
    }

    private fun reloadInObservedRoot(observed: Drug, userId: Uuid): Drug {
        val current = try {
            drugs.get(observed.id, userId)
        } catch (_: NotAMember) {
            // Доступ к наблюдавшемуся корню уже удерживается. Значит изменилась сама упаковка:
            // её удалили либо перенесли между первым чтением и блокировкой.
            throw StaleVersion()
        }
        if (current.medKitId != observed.medKitId) throw StaleVersion()
        return current
    }
}
