package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Register an intake using the idempotency key from the path")
data class IntakeRequest(
    @field:NotNull
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val quantityConsumed: BigDecimal
)

@Schema(description = "Treatment plan owned by the authenticated user")
data class TreatmentPlanDTO(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
)

@Schema(description = "Create treatment plan request")
data class TreatmentPlanCreateRequest(
    @field:NotNull
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val plannedAmount: BigDecimal
)

@Schema(description = "Patch treatment plan request")
data class TreatmentPlanPatchRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val plannedAmount: BigDecimal
)

@Schema(description = "Idempotently recorded intake")
data class IntakeResultDTO(
    val drugId: UUID,
    val quantityConsumed: BigDecimal,
    val treatmentPlan: TreatmentPlanDTO?
)
