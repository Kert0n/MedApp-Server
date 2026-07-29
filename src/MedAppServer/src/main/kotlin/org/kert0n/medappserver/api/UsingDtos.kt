package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Register an intake using the idempotency key from the path")
data class IntakeRequest(
    @NotNull
    @field:Schema(description = "Drug whose stock and own treatment plan are consumed")
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(description = "Positive amount consumed", example = "1.0", minimum = "0")
    val quantityConsumed: BigDecimal
)

@Schema(description = "Treatment plan owned by the authenticated user")
data class TreatmentPlanDTO(
    @field:Schema(description = "Owner identifier")
    val userId: UUID,
    @field:Schema(description = "Drug identifier")
    val drugId: UUID,
    @field:Schema(description = "Amount reserved by this plan", example = "20.0")
    val plannedAmount: BigDecimal
)

@Schema(description = "Create treatment plan request")
data class TreatmentPlanCreateRequest(
    @NotNull
    @field:Schema(description = "Drug identifier")
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(description = "Positive amount to reserve", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)

@Schema(description = "Patch treatment plan request")
data class TreatmentPlanPatchRequest(
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @field:Schema(description = "New positive reserved amount", example = "20.0", minimum = "0")
    val plannedAmount: BigDecimal
)

@Schema(description = "Idempotently recorded intake")
data class IntakeResultDTO(
    @field:Schema(description = "Consumed drug identifier")
    val drugId: UUID,
    @field:Schema(description = "Amount consumed", example = "1.0")
    val quantityConsumed: BigDecimal,
    @field:Schema(
        description = "Remaining treatment plan; null when the plan or drug was exhausted",
        nullable = true
    )
    val treatmentPlan: TreatmentPlanDTO?
)
