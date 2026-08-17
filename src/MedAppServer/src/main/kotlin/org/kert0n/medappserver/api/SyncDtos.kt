package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

/**
 * Одна пачка, один запрос, одна транзакция.
 *
 * Клиент копит события офлайн и присылает суммы: сколько всего съедено и какой стала бронь. Это
 * два разных ресурса, и порядок между двумя запросами не гарантирован — между списанием и
 * уменьшением брони успевает вклиниться чужое чтение, увидеть «лекарства мало» и разбудить
 * человека ночью. Поэтому обе половины едут вместе.
 *
 * Версии здесь в теле, а не в `If-Match`: запрос меняет два ресурса, а заголовок один.
 * Единственное место, где это так.
 */
@Schema(description = "Cumulative changes to one package: what was taken and what the reservation became")
data class SyncRequest(
    /** Дельта: сколько съедено с прошлой синхронизации. `null` — приёма не было. */
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Amount taken since the last sync; omit if nothing was taken", example = "5.0")
    val consumed: BigDecimal? = null,

    @field:NotNull
    @Schema(description = "Package version the caller decided by", example = "3", required = true)
    val drugVersion: Long,

    @field:Valid
    @Schema(description = "New reservation state; omit to leave the reservation alone")
    val reservation: SyncReservation? = null
)

/**
 * Бронь — абсолютным значением, а не дельтой.
 *
 * Дельта брони при повторе запроса складывалась бы сама с собой, и клиент не смог бы отличить
 * «мой запрос дошёл» от «дошёл дважды». Итоговое число этой проблемы не имеет.
 */
@Schema(description = "What the reservation should become")
data class SyncReservation(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Reserved amount, greater than zero; may exceed what is left", example = "20.0", required = true)
    val amount: BigDecimal,

    /** `null` — брони ещё нет, её заводит этот же запрос. */
    @Schema(description = "Reservation version the caller decided by; omit when creating it", example = "7")
    val version: Long? = null
)

/**
 * Итог синхронизации: оба ресурса целиком.
 *
 * Тега у ответа нет — ресурсов два, а `ETag` один; новые версии приходят в самих объектах.
 */
@Schema(description = "State of both resources after the synchronisation")
data class SyncResultDTO(
    @Schema(description = "Package after the intake")
    val drug: DrugDTO,
    @Schema(description = "Reservation after the change; absent if the caller has none")
    val reservation: ReservationDTO?
)
