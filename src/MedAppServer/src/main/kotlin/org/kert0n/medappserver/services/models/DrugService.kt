package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.repository.DrugRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class DrugService(private val drugs: DrugRepository) {

    @Transactional(readOnly = true)
    fun getAccessible(userId: UUID, drugId: UUID): DrugView =
        drugs.findAccessible(drugId, userId)?.toView()
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Drug not found or access denied"
            )
}
