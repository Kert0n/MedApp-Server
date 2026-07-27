package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Intake request")
data class IntakeRequest(
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Amount consumed", example = "1.0", minimum = "0")
    val quantityConsumed: BigDecimal,

    @NotNull
    @Schema(
        description = "Client-generated identifier of this intake event. Retrying with the same " +
            "value returns the first result instead of applying the intake twice.",
        required = true
    )
    val intakeId: UUID
)

@Schema(description = "Treatment plan information")
data class UsingDTO(
    @Schema(description = "User identifier")
    val userId: UUID,
    @Schema(description = "Drug identifier")
    val drugId: UUID,
    @Schema(description = "Planned total amount for the course")
    val plannedAmount: BigDecimal
)

@Schema(description = "Create treatment plan request")
data class UsingCreateDTO(
    @NotNull
    @Schema(description = "Drug identifier")
    val drugId: UUID,

    @NotNull
    @DecimalMin("0.0")
    @Schema(description = "Planned amount", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)

@Schema(description = "Update treatment plan request")
data class UsingUpdateDTO(
    @NotNull
    @DecimalMin("0.0")
    @Schema(description = "Planned amount", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)
