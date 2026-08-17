package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@Schema(description = "Medicine kit with its drugs")
data class MedKitDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID,
    @Schema(description = "Drugs stored in the kit")
    val drugs: Set<DrugDTO>
)

@Schema(description = "Medicine kit counters without loading its contents")
data class MedKitSummaryDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID,
    @Schema(description = "Number of participants", example = "2")
    val userCount: Long,
    @Schema(description = "Number of drugs", example = "17")
    val drugCount: Long
)

@Schema(description = "Created medicine kit")
data class MedKitCreatedDTO(
    @Schema(description = "Medicine kit identifier")
    val id: UUID
)

/** Объектом, а не голой строкой: срок жизни или ссылку рядом с ней добавить было бы некуда. */
@Schema(description = "Invitation to join a medicine kit")
data class InvitationDTO(
    @Schema(description = "Key to join the kit; valid for a limited time and reusable within it")
    val key: String
)

@Schema(description = "Request to join a medicine kit by invitation")
data class MembershipCreateRequest(
    @field:NotBlank
    @Schema(description = "Invitation key", example = "sSQUCT8iOjYsobZ7StyspQ", required = true)
    val key: String
)
