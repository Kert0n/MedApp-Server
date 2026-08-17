package org.kert0n.medappserver.api

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.MedKitOverview
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.TreatmentPlanEntry

/**
 * Перевод доменных значений в публичный контракт.
 *
 * Отдельных форм чтения нет: DTO собирается из того же состояния, по которому агрегат
 * принимает решения, поэтому разойтись «сколько запланировано в ответе» и «сколько
 * запланировано в проверке» уже не могут.
 *
 * Единица измерения и форма отдаются парой «идентификатор и имя»: первый нужен, чтобы
 * прислать его обратно при следующей правке, второе — чтобы нарисовать карточку без второго
 * запроса.
 */
fun Drug.toDto(): DrugDTO = DrugDTO(
    id = id,
    version = version,
    name = name,
    quantity = quantity.amount,
    plannedQuantity = plannedTotal.amount,
    availableQuantity = availableQuantity.amount,
    quantityUnitId = quantity.unit.id,
    quantityUnit = quantity.unit.name,
    formTypeId = formType?.id,
    formType = formType?.name,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKitId
)

fun TreatmentPlanEntry.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    drugId = plan.drugId,
    plannedAmount = plan.plannedAmount.amount,
    drugVersion = drugVersion
)

/**
 * План в ответе команды берётся из препарата, а не читается заново.
 *
 * Команда уже вернула агрегат, и в нём есть и план, и новая версия. Второй запрос за тем же
 * самым дал бы клиенту план и версию из двух разных моментов времени.
 */
fun Drug.planDtoOf(userId: UUID): TreatmentPlanDTO = TreatmentPlanDTO(
    drugId = id,
    plannedAmount = requirePlanOf(userId).plannedAmount.amount,
    drugVersion = version
)

fun DrugTemplate.toDto(): DrugTemplateDTO = DrugTemplateDTO(
    id = id,
    name = name,
    nameLat = nameLat,
    activeSubstance = activeSubstance,
    formTypeId = formType?.id,
    formType = formType?.name,
    category = category,
    quantityUnitId = quantityUnit?.id,
    quantityUnit = quantityUnit?.name,
    manufacturer = manufacturer,
    country = country,
    description = description
)

fun QuantityUnit.toDto(): VocabularyEntryDTO = VocabularyEntryDTO(id = id, name = name)

fun FormType.toDto(): VocabularyEntryDTO = VocabularyEntryDTO(id = id, name = name)

fun MedKitOverview.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    version = version,
    userCount = memberCount,
    drugCount = drugCount
)
