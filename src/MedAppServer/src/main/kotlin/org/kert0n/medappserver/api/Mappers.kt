package org.kert0n.medappserver.api

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.MedKitSummary
import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.services.models.DrugPatch
import org.kert0n.medappserver.services.models.DrugView
import org.kert0n.medappserver.services.models.MedKitContentView
import org.kert0n.medappserver.services.models.MedKitSummaryView
import org.kert0n.medappserver.services.models.PlanSnapshot
import org.kert0n.medappserver.services.models.TreatmentPlanView

/**
 * Перевод сущностей в контракт.
 *
 * Живёт рядом с DTO, а не в сервисах: маппинг — это представление, и держать его в бизнес-слое
 * значит заставлять сервис знать, в каком виде наружу уходит ответ. Раньше `toDrugDTO` и
 * `toUsingDTO` были методами сервисов, а тесты контроллеров их подменяли моками — то есть
 * проверяли выдуманное соответствие сущности и DTO, а не настоящее.
 *
 * Функции чистые и без обращений к БД: всё, что нужно, вызывающий обязан загрузить заранее.
 * Единственное исключение оговорено у [toDto] для `Drug`.
 */

/**
 * Препарат в ответ.
 *
 * `medKit.id` берётся у ленивого прокси и **не** инициализирует связь: идентификатор Hibernate
 * знает и так, из внешнего ключа. Поэтому маппер безопасно вызывать вне транзакции. Обращение
 * к любому другому полю `medKit` такой гарантии уже не даёт.
 */
fun Drug.toDto(): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity,
    plannedQuantity = totalPlannedAmount,
    availableQuantity = availableQuantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKit.id
)

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

/** План лечения в ответ. Для LAZY-ссылок читаются только известные Hibernate идентификаторы. */
fun Using.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(
    userId = user.id,
    drugId = drug.id,
    plannedAmount = plannedAmount
)

/**
 * Аптечка с препаратами.
 *
 * Препараты передаются аргументом, а не читаются из `medKit.drugs`: коллекция ленивая, и
 * маппер, дёргающий её сам, тихо превращался бы в запрос — иногда в N запросов. Кто вызывает,
 * тот и отвечает за то, каким запросом эти препараты загружены.
 */
fun MedKit.toDto(drugs: Collection<Drug>): MedKitDTO = MedKitDTO(
    id = id,
    drugs = drugs.map { it.toDto() }.toSet()
)

/**
 * Запись справочника в шаблон препарата.
 *
 * `formType` и `quantityUnit` — связи `@ManyToOne(EAGER)`, здесь берётся только их имя.
 */
fun VidalDrug.toTemplateDto(): DrugTemplateDTO = DrugTemplateDTO(
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

/**
 * Сводка аптечки наружу.
 *
 * Отдельный шаг, а не тот же тип из запроса: пока проекция репозитория и тело ответа были
 * одним классом, форму ответа нельзя было тронуть, не переписав JPQL.
 */
fun MedKitSummary.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = userCount,
    drugCount = drugCount
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
