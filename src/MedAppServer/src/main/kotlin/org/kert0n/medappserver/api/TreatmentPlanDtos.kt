package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

/**
 * План лечения: сколько препарата пользователь зарезервировал под себя.
 *
 * Времени создания и изменения здесь нет. Расписание приёма живёт на клиенте, а эти отметки
 * никем не читались и лишь сообщали наружу, когда пользователь трогал свою аптечку.
 */
@Schema(description = "How much of a drug the user reserved for themselves")
data class TreatmentPlanDTO(
    @Schema(description = "Drug the plan belongs to")
    val drugId: UUID,
    @Schema(description = "Planned amount for the course", example = "20.000000")
    val plannedAmount: BigDecimal
)

@Schema(description = "Request to reserve an amount of a drug")
data class TreatmentPlanCreateRequest(
    @field:NotNull
    @Schema(description = "Drug identifier", required = true)
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Planned amount, greater than zero", example = "20.0", required = true)
    val plannedAmount: BigDecimal
)

/**
 * Ноль здесь не означает удаление: план с нулём — это всё ещё план, а удаление выражается
 * методом DELETE. Иначе одно и то же действие имело бы два разных написания.
 */
@Schema(description = "Request to change the planned amount")
data class TreatmentPlanPatchRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "New planned amount, greater than zero", example = "15.0", required = true)
    val plannedAmount: BigDecimal
)

@Schema(description = "Recorded intake of a planned drug")
data class IntakeRequest(
    @field:NotNull
    @Schema(description = "Drug identifier", required = true)
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Amount consumed, greater than zero", example = "1.0", required = true)
    val quantityConsumed: BigDecimal
)

/**
 * Итог приёма.
 *
 * Оба поля обнуляемые, и это не случайность: приём может исчерпать план (тогда план
 * удаляется) и может исчерпать сам препарат (тогда удаляется и он). Клиенту нужно увидеть
 * именно это, а не гадать по следующему запросу.
 */
@Schema(description = "State after the intake was applied")
data class IntakeResultDTO(
    @Schema(description = "Remaining plan, or null when the intake exhausted it", nullable = true)
    val treatmentPlan: TreatmentPlanDTO?,
    @Schema(description = "Drug after the intake, or null when it ran out", nullable = true)
    val drug: DrugDTO?
)
