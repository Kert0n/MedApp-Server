package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid

/**
 * Упаковка — корень агрегата.
 *
 * Это пачка, а не «лекарство вообще»: пополнить её нельзя, вторая пачка — вторая упаковка.
 * Опустевшая выбрасывается, поэтому упаковки с нулём не существует.
 *
 * О бронях упаковка не знает: сколько из своей брони оставить, решает её владелец.
 *
 * Состояние неизменяемо — команда возвращает следующее, а не меняет текущее.
 */
data class Drug(
    val id: Uuid = Uuid.random(),
    val medKitId: Uuid,
    val name: String,
    val quantity: Quantity,
    val formType: FormType? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    /** Токен предусловия: чем была упаковка, когда её прочитали. Домен на него не смотрит. */
    val version: Long = 0
) {

    init {
        if (!quantity.isPositive) throw InvalidQuantity()
    }

    /** Описательные поля; `null` означает «оставить как есть». */
    fun describe(details: DrugDetails): Drug = copy(
        name = details.name ?: name,
        formType = details.formType ?: formType,
        category = details.category ?: category,
        manufacturer = details.manufacturer ?: manufacturer,
        country = details.country ?: country,
        description = details.description ?: description
    )

    /**
     * Единицу указали неверно при заведении: те же числа с другой подписью.
     *
     * Пересчёта нет: перевести таблетки в миллилитры система не умеет.
     */
    fun relabelUnitTo(unit: QuantityUnit): Drug = copy(quantity = Quantity(quantity.amount, unit))

    /** Исправление учёта — пересчитал пачку и увидел другое число. Не пополнение. */
    fun changeQuantityTo(newQuantity: Quantity): Drug = copy(quantity = requirePositive(newQuantity))

    /**
     * Съеденное. `null` — пачка кончилась и подлежит уничтожению; удаляет строку хранилище.
     *
     * Больше, чем в пачке, съесть нельзя: столько таблеток в ней физически не было.
     */
    fun consume(amount: Quantity): Drug? {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        val left = quantity - consumed
        return if (left.isZero) null else copy(quantity = left)
    }

    /** Переезд. Брони тех, кто целевую аптечку не видит, убирает вызывающий: чужой агрегат. */
    fun moveTo(targetMedKitId: Uuid): Drug = copy(medKitId = targetMedKitId)

    private fun requirePositive(amount: Quantity): Quantity {
        if (!amount.isPositive) throw InvalidQuantity()
        if (amount.unit != quantity.unit) throw QuantityUnitMismatch()
        return amount
    }

    /** Сущность, а не значение: два состояния с разными остатками — одна и та же пачка. */
    override fun equals(other: Any?): Boolean = this === other || (other is Drug && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

/** Описательные поля упаковки; `null` — «не менять». */
data class DrugDetails(
    val name: String? = null,
    val formType: FormType? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    /** Токен предусловия: чем была упаковка, когда её прочитали. Домен на него не смотрит. */
    val version: Long = 0
)
