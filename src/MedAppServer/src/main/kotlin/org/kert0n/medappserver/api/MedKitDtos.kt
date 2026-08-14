package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
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
