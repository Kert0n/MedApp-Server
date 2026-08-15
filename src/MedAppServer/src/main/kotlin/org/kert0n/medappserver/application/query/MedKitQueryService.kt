package org.kert0n.medappserver.application.query

import org.kert0n.medappserver.application.model.MedKitContentView
import org.kert0n.medappserver.application.model.MedKitSummaryView
import org.kert0n.medappserver.application.model.UserSnapshotView
import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.persistence.repository.MedKitProjectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class MedKitQueryService(
    private val repository: MedKitProjectionRepository
) {
    fun listForUser(userId: UUID): List<MedKitSummaryView> = repository.listForUser(userId)

    fun getContent(userId: UUID, medKitId: UUID): MedKitContentView =
        repository.content(userId, medKitId) ?: throw MedKitNotFound(medKitId)

    fun getUserSnapshot(userId: UUID): UserSnapshotView = repository.snapshot(userId)
}
