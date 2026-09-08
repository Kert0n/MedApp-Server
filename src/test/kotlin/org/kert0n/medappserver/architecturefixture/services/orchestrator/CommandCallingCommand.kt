package org.kert0n.medappserver.architecturefixture.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.services.orchestrator.MedKitLeaving
import org.springframework.stereotype.Service

/** Сценарий, зовущий вход другого сценария: вложенная блокировка или повышение режима. */
@Service
class CommandCallingCommand(private val leaving: MedKitLeaving) {
    fun leave(medKitId: Uuid, userId: Uuid) = leaving.leave(medKitId, userId)
}
