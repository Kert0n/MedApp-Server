package org.kert0n.medappserver.services.models

import java.math.BigDecimal

/**
 * Аргументы прикладных операций, не зависящие от HTTP.
 *
 * Сервисы принимали api-DTO напрямую, и это связывало их с формой запроса: у
 * [DrugCreation] нет `medKitId`, потому что маршрутизация по аптечкам — забота
 * оркестратора, а не создания препарата; у api-типа он есть, и сервис вынужден был его
 * игнорировать. Обратная сторона того же: поменять форму запроса, не задев сервис, было
 * нельзя.
 *
 * Валидации здесь нет намеренно: ограничения входа (`@Size`, `@DecimalMin`) — свойство
 * контракта и живут на DTO. Сюда значения приходят уже проверенными.
 *
 * Перевод из api делают расширения `toCommand()` и `toPatch()` в `api/Mappers.kt`:
 * представление вправе знать прикладной слой, обратное — нет.
 */
data class DrugCreation(
    val name: String,
    val quantity: BigDecimal,
    val quantityUnit: String,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

/**
 * Частичная правка препарата: `null` означает «не трогать», а не «очистить».
 *
 * Ровно та же семантика, что была у `DrugUpdateDTO`, — она и раньше читалась только через
 * `?.let`.
 */
data class DrugPatch(
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)
