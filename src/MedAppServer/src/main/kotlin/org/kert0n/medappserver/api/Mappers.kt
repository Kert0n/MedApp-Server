package org.kert0n.medappserver.api

import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.services.models.DrugPatch
import org.kert0n.medappserver.services.models.DrugTemplateView
import org.kert0n.medappserver.services.models.DrugView
import org.kert0n.medappserver.services.models.MedKitContentView
import org.kert0n.medappserver.services.models.MedKitSummaryView
import org.kert0n.medappserver.services.models.PlanSnapshot
import org.kert0n.medappserver.services.models.TreatmentPlanView

/** Чистые преобразования прикладных проекций и API-контрактов. */
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

fun TreatmentPlanView.toDto(): TreatmentPlanDTO =
    TreatmentPlanDTO(userId, drugId, plannedAmount)

fun MedKitContentView.toDto(): MedKitDTO =
    MedKitDTO(id = id, drugs = drugs.mapTo(linkedSetOf()) { it.toDto() })

fun MedKitSummaryView.toDto(): MedKitSummaryDTO =
    MedKitSummaryDTO(id, userCount, drugCount)

/** Запись справочника в публичный шаблон препарата. */
fun DrugTemplateView.toTemplateDto(): DrugTemplateDTO = DrugTemplateDTO(
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

/**
 * Запрос на создание препарата — в аргументы прикладной операции.
 *
 * `medKitId` не переносится: он маршрутный, его разбирает оркестратор, а созданию препарата
 * он не нужен.
 */
fun DrugCreateDTO.toCommand(): DrugCreation = DrugCreation(
    name = name,
    quantity = quantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description
)

/** Запрос на правку препарата — в частичный патч. `null` по-прежнему значит «не трогать». */
fun DrugPatchRequest.toPatch(): DrugPatch = DrugPatch(
    name = name,
    quantity = quantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description
)

/** Снимок плана после приёма — наружу. */
fun PlanSnapshot.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    userId = userId,
    drugId = drugId,
    plannedAmount = plannedAmount
)
