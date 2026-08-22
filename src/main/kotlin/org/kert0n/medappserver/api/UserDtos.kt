package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Снимок вызывающего.
 *
 * Живёт в `api`, а не рядом с контроллером: собирает его прикладной сервис, а зависимость
 * «сервис → контроллер» была бы вывернутой.
 */
@Schema(description = "Everything the caller can see, in one response")
@Serializable
data class UserSnapshotDTO(
    @Schema(description = "Caller identifier")
    val id: Uuid,
    @Schema(description = "All medicine kits available to the caller")
    val medKits: Set<MedKitDTO>
)
