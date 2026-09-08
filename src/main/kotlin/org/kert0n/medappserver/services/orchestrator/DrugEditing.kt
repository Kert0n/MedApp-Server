package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.services.aggregate.DrugEdit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/** Правка упаковки под доступом, удерживаемым на весь срок команды. */
@Service
class DrugEditing(
    private val access: DrugCommandAccess,
    private val drugs: DrugService
) {

    @Transactional(propagation = MANDATORY)
    fun update(drugId: Uuid, userId: Uuid, request: DrugEdit): Drug =
        drugs.update(access.content(drugId, userId), request)
}
