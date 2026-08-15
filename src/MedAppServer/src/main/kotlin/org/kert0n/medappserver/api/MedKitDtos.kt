package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@Schema(description = "Created medicine kit")
data class MedKitCreatedDTO(
    @field:Schema(description = "Medicine kit identifier") val id: UUID
)

@Schema(description = "Invitation to a medicine kit")
data class MedKitInvitationDTO(
    @field:Schema(description = "URL-safe invitation key") val key: String
)

@Schema(description = "Join a medicine kit using an invitation")
data class MedKitMembershipCreateRequest(
    @field:NotBlank @field:Schema(description = "Invitation key") val key: String
)

@Schema(description = "Medicine kit summary")
data class MedKitSummaryDTO(
    @field:Schema(description = "Medicine kit identifier") val id: UUID,
    @field:Schema(description = "Number of members") val userCount: Long,
    @field:Schema(description = "Number of drugs") val drugCount: Long
)

@Schema(description = "Medicine kit content")
data class MedKitContentDTO(
    @field:Schema(description = "Medicine kit identifier") val id: UUID,
    @field:Schema(description = "Drugs currently stored in the kit") val drugs: List<DrugDTO>
)

@Schema(description = "Current user synchronization snapshot")
data class UserSnapshotDTO(
    @field:Schema(description = "Current user identifier") val id: UUID,
    @field:Schema(description = "All accessible medicine kits with their content") val medKits: List<MedKitContentDTO>
)
