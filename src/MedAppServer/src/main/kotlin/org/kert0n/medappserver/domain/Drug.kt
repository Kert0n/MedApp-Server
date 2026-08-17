package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Упаковка — корень агрегата.
 *
 * Это именно **пачка**, а не «лекарство вообще», и из этого следует всё остальное. Пачку
 * нельзя пополнить: купил вторую — завёл вторую упаковку. Съесть из неё больше, чем в ней
 * есть, невозможно — это физика, а не политика системы. Опустевшая пачка выбрасывается,
 * поэтому упаковки с нулём не существует.
 *
 * **Упаковка ничего не знает о бронях.** Ни их суммы, ни «доступного остатка», ни
 * пропорционального пересчёта здесь нет и не будет: сколько из своей брони оставить, решает её
 * владелец, а не сервер. Раньше это знание жило тут и не сходилось ни с чем — ни с границами
 * агрегатов, ни с версиями.
 *
 * Состояние неизменяемо: команда не меняет объект, а возвращает следующее состояние. Поэтому
 * тут нет ни `var`, ни присваиваний снаружи — правило «менять упаковку можно только её
 * методами» держит компилятор.
 */
data class Drug(
    val id: UUID = UUID.randomUUID(),
    val medKitId: UUID,
    val name: String,
    val quantity: Quantity,
    val formType: FormType? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
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
     * Смена единицы измерения: те же числа с другой подписью.
     *
     * Нужна ровно для одного случая — единицу указали неверно при заведении. Пересчёта нет и
     * быть не может: перевести таблетки в миллилитры система не умеет, а притвориться, что
     * умеет, было бы хуже, чем не уметь.
     */
    fun relabelUnitTo(unit: QuantityUnit): Drug = copy(quantity = Quantity(quantity.amount, unit))

    /**
     * Исправление учёта: пересчитал пачку и увидел другое число.
     *
     * Именно исправление, а не пополнение. Пополнения у упаковки не бывает — новая пачка это
     * новая упаковка, — но ошибиться при заведении можно в обе стороны, и починка этой ошибки
     * ничего больше не задевает: брони живут своей жизнью и пересчитывать их незачем.
     */
    fun changeQuantityTo(newQuantity: Quantity): Drug = copy(quantity = requirePositive(newQuantity))

    /**
     * Съеденное.
     *
     * `null` означает, что пачка кончилась: строку удаляет вызывающий, потому что удаление —
     * работа хранилища. Упаковка с нулевым остатком не собирается вовсе, промежуточного
     * состояния с нулём здесь нет.
     *
     * Списать больше, чем в пачке, нельзя, и это не защита от клиента: столько таблеток в ней
     * физически не было. Если клиент сообщает о таком, разошлись не числа, а представления о
     * том, из какой пачки ели.
     */
    fun consume(amount: Quantity): Drug? {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        val left = quantity - consumed
        return if (left.isZero) null else copy(quantity = left)
    }

    /**
     * Переезд в другую аптечку.
     *
     * Судьбу броней здесь решать нечем: они лежат в чужих агрегатах, и тех, кто целевую
     * аптечку не видит, убирает вызывающий — это межагрегатный сценарий.
     */
    fun moveTo(targetMedKitId: UUID): Drug = copy(medKitId = targetMedKitId)

    private fun requirePositive(amount: Quantity): Quantity {
        if (!amount.isPositive) throw InvalidQuantity()
        if (amount.unit != quantity.unit) throw QuantityUnitMismatch()
        return amount
    }

    /**
     * Упаковка — сущность, а не значение: две её состояния с разными остатками остаются одной
     * и той же пачкой. Поэтому сравнение по идентификатору, а не по всем полям.
     */
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
    val description: String? = null
)
