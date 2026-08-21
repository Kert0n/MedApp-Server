package org.kert0n.medappserver.domain

import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Что заявлено на одну упаковку: сумма и доля вызывающего.
 *
 * Две величины, а не одна: общая сумма нужна, чтобы понять, разобрана пачка или нет, а своя
 * доля — чтобы показать её владельцу. Сумма может превышать остаток, и это не ошибка: чью бронь
 * ужать, решает её владелец, а не сервер.
 *
 * **Токен предусловия принадлежит снимку, а не отдельной брони.** Человек не может изменить свою
 * долю, не увидев, сколько заявлено всего: иначе он не может решить, сколько ему брать. Поэтому
 * версия у картины целиком, и её двигает любая правка брони на этой упаковке.
 *
 * Упаковка обо всём этом не знает: у `Drug` ни суммы, ни доступного остатка нет и не будет.
 */
data class ReservationSnapshot(
    val drugId: Uuid,
    val total: BigDecimal,
    val mine: BigDecimal?,
    val version: Long
) {
    companion object {
        /**
         * Собирается из броней упаковки, а не из хранимой суммы.
         *
         * Брони на эту упаковку читающая сторона всё равно получает, поэтому складываются они
         * здесь — в одном месте, а не в мапперe ответа.
         */
        fun of(drugId: Uuid, reservations: List<Reservation>, userId: Uuid, version: Long) =
            ReservationSnapshot(
                drugId = drugId,
                total = reservations.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount.amount },
                mine = reservations.firstOrNull { it.userId == userId }?.amount?.amount,
                version = version
            )

        /** Ни одной брони: сумма ноль, своей доли нет. Версия всё равно есть — она у упаковки. */
        fun empty(drugId: Uuid, version: Long) =
            ReservationSnapshot(drugId, BigDecimal.ZERO, null, version)
    }
}
