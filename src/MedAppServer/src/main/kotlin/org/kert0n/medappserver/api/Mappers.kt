package org.kert0n.medappserver.api

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.parsed.VidalDrug

/**
 * Перевод модели хранения в публичный контракт.
 *
 * Собран в одном месте, чтобы форма ответа не разъезжалась между контроллерами: раньше один
 * и тот же препарат собирался в DTO в трёх разных файлах.
 */
fun Drug.toDto(): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity,
    plannedQuantity = totalPlannedAmount,
    availableQuantity = quantity - totalPlannedAmount,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKit.id
)

fun Using.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    drugId = drug.id,
    plannedAmount = plannedAmount
)

fun VidalDrug.toDto(): DrugTemplateDTO = DrugTemplateDTO(
    id = id,
    name = name,
    nameLat = nameLat,
    activeSubstance = activeSubstance,
    formType = formType?.name,
    category = category,
    quantityUnit = quantityUnit?.name,
    manufacturer = manufacturer,
    country = country,
    description = description
)
