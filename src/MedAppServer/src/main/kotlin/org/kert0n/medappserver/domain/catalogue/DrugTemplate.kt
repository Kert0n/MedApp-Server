package org.kert0n.medappserver.domain.catalogue

import java.util.UUID

/**
 * Карточка справочника: заготовка, из которой пользователь заводит свой препарат.
 *
 * Правил у неё нет и быть не может — справочник только читается, а импортируется отдельным
 * инструментом. Поэтому здесь простое значение: форма и единица измерения уже развёрнуты в
 * названия, чтобы за ними не ходить вторым запросом.
 */
data class DrugTemplate(
    val id: UUID,
    val name: String,
    val nameLat: String?,
    val activeSubstance: String?,
    val formType: String?,
    val category: String?,
    val quantityUnit: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)
