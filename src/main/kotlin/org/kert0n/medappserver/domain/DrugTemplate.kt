package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid

/**
 * Карточка справочника: заготовка, из которой человек заводит свою упаковку.
 *
 * Правил нет — справочник только читается, наполняет его отдельный импорт. Форма и единица
 * здесь те же значения, что у упаковки: это и есть общая часть двух контекстов.
 */
data class DrugTemplate(
    val id: Uuid,
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
    val id: Uuid,
    val name: String
)
