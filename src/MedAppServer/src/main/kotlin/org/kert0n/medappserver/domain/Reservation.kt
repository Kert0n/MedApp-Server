package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Бронь — заявка человека на часть упаковки: «эти двадцать таблеток мои».
 *
 * Корень агрегата. Идентичность — пара «человек и упаковка»: одна бронь на пачку от одного
 * человека. На упаковку ссылается идентификатором — это другой агрегат.
 *
 * **Может превышать остаток пачки, и это нормальное состояние:** тридцать таблеток при
 * забронированных сорока. Чем поступиться, решает владелец брони, а не сервер.
 *
 * Уничтожения упаковки бронь не переживает, но держит это внешний ключ с каскадом: правило
 * целостности данных, а не владение.
 */
data class Reservation(
    val userId: UUID,
    val drugId: UUID,
    val amount: Quantity,
    /** Непрозрачный токен состояния: домен его не толкует и не двигает, этим занят Hibernate. */
    val version: Long = 0
) {

    init {
        if (!amount.isPositive) throw InvalidQuantity()
    }

    /** Ограничения сверху нет. Ноль — не размер брони, а её отмена, и та выражается удалением. */
    fun changeTo(newAmount: Quantity): Reservation {
        if (!newAmount.isPositive) throw InvalidQuantity()
        if (newAmount.unit != amount.unit) throw QuantityUnitMismatch()
        return copy(amount = newAmount)
    }

    /** Клиент решал по этому состоянию — или по устаревшему. */
    fun requireVersion(expected: Long) {
        if (expected != version) throw StaleAggregateVersion()
    }

    /** Сущность: идентичность — пара «человек и упаковка», а не количество и не версия. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Reservation && userId == other.userId && drugId == other.drugId)

    override fun hashCode(): Int = 31 * userId.hashCode() + drugId.hashCode()
}
