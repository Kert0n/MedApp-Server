@file:UseSerializers(BigDecimalAsString::class, UuidAsString::class)

package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Бронь: сколько из этой упаковки человек считает своим.
 *
 * Без отметок времени: расписание приёма живёт на клиенте, а такие отметки только сообщали бы
 * наружу, когда человек трогал свою аптечку.
 */
@Schema(description = "How much of a package the caller claims for themselves")
@Serializable
data class ReservationDTO(
    @Schema(description = "Package the reservation is placed on")
    val drugId: UUID,
    /**
     * Может превышать содержимое пачки, и это не ошибка: сколько из своей брони оставить,
     * решает её владелец.
     */
    @Schema(
        description = "Reserved amount; may exceed what is left in the package", example = "20.000000",
        type = "string", pattern = POSITIVE_QUANTITY_PATTERN
    )
    val amount: BigDecimal
)

@Schema(description = "Request to reserve part of a package")
@Serializable
data class ReservationCreateRequest(
    @field:NotNull
    @Schema(description = "Package identifier", required = true)
    val drugId: UUID,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 13, fraction = 6)
    @Schema(
        description = "Reserved amount, greater than zero. It may exceed what is left in the package.",
        example = "20.0",
        required = true,
        type = "string",
        pattern = POSITIVE_QUANTITY_PATTERN
    )
    val amount: BigDecimal
)

/**
 * Ноль здесь не означает отмену: брони с нулём не бывает, а отмена выражается методом DELETE.
 * Иначе одно и то же действие имело бы два разных написания.
 */
@Schema(description = "Request to change the reserved amount")
@Serializable
data class ReservationPatchRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 13, fraction = 6)
    @Schema(
        description = "New reserved amount, greater than zero", example = "15.0", required = true,
        type = "string", pattern = POSITIVE_QUANTITY_PATTERN
    )
    val amount: BigDecimal
)

/** Приём: съеденное уменьшает упаковку, а бронь её владелец правит отдельно. */
@Schema(description = "Amount taken out of the package")
@Serializable
data class IntakeRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 13, fraction = 6)
    @Schema(
        description = "Amount taken, greater than zero", example = "2.0", required = true,
        type = "string", pattern = POSITIVE_QUANTITY_PATTERN
    )
    val quantity: BigDecimal
)
