package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Treatment plan owned by the current user")
data class TreatmentPlanDTO(
    @field:Schema(description = "User identifier") val userId: UUID,
    @field:Schema(description = "Drug identifier") val drugId: UUID,
    @field:Schema(description = "Amount reserved for the treatment course") val plannedAmount: BigDecimal
)

@Schema(description = "Create a treatment plan")
data class TreatmentPlanCreateRequest(
    @field:NotNull @field:Schema(description = "Drug identifier") val drugId: UUID,
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "Positive amount to reserve") val plannedAmount: BigDecimal
)

@Schema(description = "Change the amount reserved by a treatment plan")
data class TreatmentPlanPatchRequest(
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "New positive planned amount") val plannedAmount: BigDecimal
)

@Schema(description = "Register one planned intake")
data class IntakeRequest(
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false)
    @field:Schema(description = "Positive consumed amount") val quantity: BigDecimal,
    @field:NotNull @field:Schema(description = "Drug identifier") val drugId: UUID
)

@Schema(description = "Committed intake result; drug or plan can disappear when exhausted")
data class IntakeResultDTO(
    @field:Schema(description = "Drug after intake, or null when exhausted", nullable = true) val drug: DrugDTO?,
    @field:Schema(description = "Plan after intake, or null when completed", nullable = true) val plan: TreatmentPlanDTO?
)
