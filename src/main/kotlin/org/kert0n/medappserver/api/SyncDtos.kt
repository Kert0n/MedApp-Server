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
 * одному месту нельзя. Коды ответа при этом те же, что у любой команды: не прислана — 428,
 * устарела — 412. Место версии не меняет смысла отказа: решение принято по картине, которой
 * больше нет. 409 остаётся одному случаю — тот же идентификатор с другим телом.
 */
@Schema(description = "Offline changes for one package, applied atomically")
@Serializable
data class DrugSyncRequest(
    /** Дельта: коммутативна, поэтому предусловия сама по себе не требует. */
    @field:PositiveQuantity
    @Schema(
        description = "Amount consumed while offline; a delta, omitted when nothing was taken",
        examples = ["5.0"]
    )
    val consumed: BigDecimal? = null,

    @Schema(
        description =
            "Version of the package state; required when consumed is present. Missing means 428, stale means 412",
        example = "3"
    )
    val drugVersion: Long? = null,

    @Schema(description = "New state of the caller's claim; omitted when it did not change")
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
        description = "Claim after the offline session, absolute", examples = ["20.0"]
    )
    val amount: BigDecimal,

    /** Без версии сервер пишет по картине броней, которую только что прочитал сам. */
    @Schema(
        description = "Version of the claims picture; when absent, the server uses the current picture read " +
            "while processing the request. A supplied stale version means 412"
    )
    val version: Long? = null
)
