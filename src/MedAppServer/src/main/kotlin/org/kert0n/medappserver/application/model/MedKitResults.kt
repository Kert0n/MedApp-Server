package org.kert0n.medappserver.application.model

import java.util.UUID

data class MedKitCreatedResult(val id: UUID)

data class InvitationResult(val key: String)

data class MedKitSummaryView(
    val id: UUID,
    val userCount: Long,
    val drugCount: Long
)

data class MedKitContentView(
    val id: UUID,
    val drugs: List<DrugResult>
)

data class UserSnapshotView(
    val id: UUID,
    val medKits: List<MedKitContentView>
)
