@file:UseSerializers(BigDecimalAsString::class)

package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Синхронизация одной упаковки: съеденное и новая бронь одним запросом.
 *
 * Двумя запросами нельзя: порядок между ними не гарантирован, и между списанием и уменьшением
 * брони остаётся окно, в котором срабатывают уведомления «лекарства мало». Здесь обе части
 * применяются в одной транзакции.
 *
 * Версии едут в теле, а не параметрами: запрос меняет два состояния сразу, и разложить их по
 * одному месту нельзя. Отсюда и код ответа: несовпадение версии из тела — 409, а не 412.
 */
@Schema(description = "Offline changes for one package, applied atomically")
@Serializable
data class DrugSyncRequest(
    /** Дельта: коммутативна, поэтому предусловия сама по себе не требует. */
    @field:PositiveQuantity
    @Schema(
        description = "Amount consumed while offline; a delta, omitted when nothing was taken",
        example = "5.0", nullable = true
    )
    val consumed: BigDecimal? = null,

    @Schema(
        description =
            "Version the command acts on; taken from the last read. Absent means 428, mismatched means 412",
        example = "3", nullable = true
    )
    val drugVersion: Long? = null,

    @Schema(description = "New state of the caller's claim; omitted when it did not change", nullable = true)
    val reservation: ReservationSyncRequest? = null
)

/**
 * Бронь приезжает абсолютным значением, а не дельтой: она и есть решение владельца, а не
 * накопленное событие.
 */
@Schema(description = "The caller's claim after the offline session")
@Serializable
data class ReservationSyncRequest(
    @field:PositiveQuantity
    @Schema(
        description = "Claim after the offline session, absolute", example = "20.0", required = true
    )
    val amount: BigDecimal,

    /** Отсутствует, когда брони ещё нет: сверяться не с чем, её заводят. */
    @Schema(description = "Version of the claims picture; absent when there is no claim yet", nullable = true)
    val version: Long? = null
)
