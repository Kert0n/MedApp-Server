package org.kert0n.medappserver.api

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.TreatmentPlan
import org.kert0n.medappserver.domain.MedKitOverview

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

/**
 * Карточка справочника отдаётся сущностью — заводить для неё доменный тип не за что, правил
 * у неё нет. Названия формы и единицы разворачиваются здесь: до этого места они остаются
 * собой, а не строками.
 */
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

fun MedKitOverview.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = memberCount,
    drugCount = drugCount
)
