@file:UseSerializers(UuidAsString::class)

package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Schema(description = "Medicine kit with its drugs")
@Serializable
data class MedKitDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID,
    @Schema(description = "Number of participants", example = "2")
    val userCount: Long,
    @Schema(description = "Drugs stored in the kit, with what is claimed on them")
    val drugs: Set<DrugSnapshotDTO>
)

@Schema(description = "Medicine kit counters without loading its contents")
@Serializable
data class MedKitSummaryDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID,
    @Schema(description = "Number of participants", example = "2")
    val userCount: Long,
    /**
     * Только идентификаторы: справка отвечает на вопрос «что ещё на месте», а не «что внутри».
     * Состояние пачек клиент берёт полным обновлением.
     */
    @Schema(description = "Identifiers of the drugs in the kit")
    val drugIds: Set<UUID>
)

@Schema(description = "Created medicine kit")
@Serializable
data class MedKitCreatedDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID
)

/** Объектом, а не голой строкой: срок жизни или ссылку рядом с ней добавить было бы некуда. */
@Schema(description = "Invitation to join a medicine kit")
@Serializable
data class InvitationDTO(
    @Schema(description = "Key to join the kit; valid for a limited time and reusable within it")
    val key: String
)

@Schema(description = "Request to join a medicine kit by invitation")
@Serializable
data class MembershipCreateRequest(
    @field:NotBlank
    @Schema(description = "Invitation key", example = "sSQUCT8iOjYsobZ7StyspQ", required = true)
    val key: String
)
