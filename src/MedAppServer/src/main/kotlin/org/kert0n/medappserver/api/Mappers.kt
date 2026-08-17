package org.kert0n.medappserver.api

import org.kert0n.medappserver.domain.catalogue.DrugTemplate
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.drug.TreatmentPlan
import org.kert0n.medappserver.domain.medkit.MedKitOverview

/**
 * Перевод доменных значений в публичный контракт.
 *
 * Отдельных форм чтения больше нет: DTO собирается из того же состояния, по которому агрегат
 * принимает решения, поэтому разойтись «сколько запланировано в ответе» и «сколько
 * запланировано в проверке» уже не могут.
 */
fun Drug.toDto(): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity,
    plannedQuantity = plannedTotal,
    availableQuantity = availableQuantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKitId
)

fun TreatmentPlan.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    drugId = drugId,
    plannedAmount = plannedAmount
)

fun DrugTemplate.toDto(): DrugTemplateDTO = DrugTemplateDTO(
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

fun MedKitOverview.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = memberCount,
    drugCount = drugCount
)
