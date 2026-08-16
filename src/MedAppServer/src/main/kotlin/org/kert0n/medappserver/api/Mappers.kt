package org.kert0n.medappserver.api

import org.kert0n.medappserver.db.repository.DrugTemplateView
import org.kert0n.medappserver.db.repository.DrugView
import org.kert0n.medappserver.db.repository.MedKitSummary
import org.kert0n.medappserver.db.repository.TreatmentPlanView

/**
 * Перевод форм чтения в публичный контракт.
 *
 * DTO собирается только из проекций, и другого пути нет. Пока их было два — из сущности и из
 * запроса, — доступный остаток считался в каждом отдельно, и такие пары рано или поздно
 * расходятся.
 */
fun DrugView.toDto(): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity,
    plannedQuantity = plannedQuantity,
    availableQuantity = availableQuantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKitId
)

fun TreatmentPlanView.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    drugId = drugId,
    plannedAmount = plannedAmount
)

fun DrugTemplateView.toDto(): DrugTemplateDTO = DrugTemplateDTO(
    id = id,
    name = name,
    nameLat = nameLat,
    activeSubstance = activeSubstance,
    formType = formType,
    category = category,
    quantityUnit = quantityUnit,
    manufacturer = manufacturer,
    country = country,
    description = description
)

fun MedKitSummary.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = userCount,
    drugCount = drugCount
)
