package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Full user snapshot")
data class UserDto(
    @Schema(description = "User identifier")
    val id: UUID,
    @Schema(description = "All medkits available to the user")
    val medKits: Set<MedKitDTO>
)
