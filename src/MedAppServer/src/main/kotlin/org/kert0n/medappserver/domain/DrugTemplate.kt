package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Карточка справочника: заготовка, из которой пользователь заводит свой препарат.
 *
 * Правил у неё нет — справочник только читается, а наполняется отдельным импортом. Тип всё
 * равно свой: форма и единица измерения здесь те же значения, что у препарата, и превращать
 * их в строки на полпути значило бы терять ровно ту связь, ради которой они типизированы.
 */
data class DrugTemplate(
    val id: UUID,
    val name: String,
    val nameLat: String?,
    val activeSubstance: String?,
    val formType: FormType?,
    val category: String?,
    val quantityUnit: QuantityUnit?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)

/** Форма выпуска из общего справочника. */
data class FormType(
    val id: UUID,
    val name: String
)
