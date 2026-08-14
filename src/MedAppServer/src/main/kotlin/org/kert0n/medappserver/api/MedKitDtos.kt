package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(description = "Medkit with drugs")
data class MedKitDTO(
    @Schema(description = "Medkit ID")
    val id: UUID,
    @Schema(description = "Drugs in medkit")
    val drugs: Set<DrugDTO>
)

data class MedKitSummaryDTO(
    @NotNull
    @Schema(description = "Medkit ID")
    val id: UUID,
    @NotNull
    @Schema(description = "Number of users in medkit")
    val userCount: Long,
    @NotNull
    @Schema(description = "Number of drugs in medkit")
    val drugCount: Long
)

data class MedKitCreatedResponse(
    @NotNull
    @Schema(description = "Created medkit ID")
    val id: UUID
)

@Schema(description = "Join medkit request")
data class JoinMedKitRequest(
    @NotBlank
    @Schema(description = "Share key to join medkit", example = "share-key-123")
    val key: String
)
