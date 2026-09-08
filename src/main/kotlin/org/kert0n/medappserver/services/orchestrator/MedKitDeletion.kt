package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.services.aggregate.MedKitAccessService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/** Удаление общей аптечки, при необходимости — с атомарным переносом её содержимого. */
@Service
class MedKitDeletion(
    private val access: MedKitAccessService,
    private val relocation: DrugRelocation,
    private val medKitService: MedKitService
) {

    @Transactional(propagation = MANDATORY)
    fun delete(medKitId: Uuid, userId: Uuid, targetMedKitId: Uuid? = null) {
        access.holdLifecycleAccess(setOfNotNull(medKitId, targetMedKitId), userId)
        targetMedKitId?.let { relocation.moveAllUnderAccess(medKitId, it) }
        medKitService.deleteRoot(medKitId)
    }
}
