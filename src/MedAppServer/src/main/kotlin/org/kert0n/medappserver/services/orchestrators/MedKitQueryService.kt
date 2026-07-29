package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.services.models.MedKitContentView
import org.kert0n.medappserver.services.models.MedKitSummaryView
import org.kert0n.medappserver.services.models.UserSnapshotView
import org.kert0n.medappserver.services.models.toView
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class MedKitQueryService(
    private val medKits: MedKitRepository,
    private val drugs: DrugRepository
) {

    @Transactional(readOnly = true)
    fun listForUser(userId: UUID): Set<MedKitSummaryView> =
        medKits.findMedKitSummariesByUserId(userId).mapTo(linkedSetOf()) { it.toView() }

    @Transactional(readOnly = true)
    fun getContent(userId: UUID, medKitId: UUID): MedKitContentView {
        medKits.findAccessible(medKitId, userId) ?: notFound()
        return MedKitContentView(
            id = medKitId,
            drugs = drugs.findAllByMedKitId(medKitId).map { it.toView() }
        )
    }

    @Transactional(readOnly = true)
    fun getUserSnapshot(userId: UUID): UserSnapshotView {
        val medKitIds = medKits.findIdsByUserId(userId)
        val byMedKit = if (medKitIds.isEmpty()) {
            emptyMap()
        } else {
            drugs.findAllByMedKitIdIn(medKitIds)
                .map { it.toView() }
                .groupBy { it.medKitId }
        }
        return UserSnapshotView(
            medKitIds.map { id -> MedKitContentView(id, byMedKit[id].orEmpty()) }
        )
    }

    private fun notFound(): Nothing =
        throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Medkit not found or user has insufficient privileges"
        )
}
