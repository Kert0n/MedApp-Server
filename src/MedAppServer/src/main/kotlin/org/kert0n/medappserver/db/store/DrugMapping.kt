package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.TreatmentPlanData
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.kert0n.medappserver.db.model.UserData
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.TreatmentPlan

/**
 * Перенос состояния препарата между доменом и отображением.
 *
 * Живёт в слое хранения, а не в домене: знать про строки таблиц — работа persistence, и
 * зависимость направлена только сюда. Цена разделения видна именно здесь — каждое поле
 * названо дважды, и забытая строка означает поле, которое молча не сохранится.
 *
 * SQL по-прежнему делает Hibernate: обратная запись идёт в **управляемую** сущность, а
 * добавление, изменение и удаление планов выражаются через её коллекцию, за которой стоят
 * каскад и `orphanRemoval`.
 */
internal fun QuantityUnitData.toDomain(): QuantityUnit = QuantityUnit(id, name)

internal fun FormTypeData.toDomain(): FormType = FormType(id, name)

internal fun DrugData.toDomain(): Drug {
    val unit = quantityUnit.toDomain()
    return Drug(
        id = id,
        medKitId = medKit.id,
        name = name,
        quantity = Quantity(quantity, unit),
        formType = formType?.toDomain(),
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description,
        plans = treatmentPlans.map {
            TreatmentPlan(it.planKey.userId, it.planKey.drugId, Quantity(it.plannedAmount, unit))
        }
    )
}

internal fun Drug.applyTo(
    entity: DrugData,
    resolveUser: (UUID) -> UserData,
    resolveMedKit: (UUID) -> MedKitData,
    resolveUnit: (UUID) -> QuantityUnitData,
    resolveForm: (UUID) -> FormTypeData
) {
    entity.name = name
    entity.quantity = quantity.amount
    if (entity.quantityUnit.id != quantity.unit.id) {
        entity.quantityUnit = resolveUnit(quantity.unit.id)
    }
    if (entity.formType?.id != formType?.id) {
        entity.formType = formType?.let { resolveForm(it.id) }
    }
    entity.category = category
    entity.manufacturer = manufacturer
    entity.country = country
    entity.description = description
    if (entity.medKit.id != medKitId) {
        entity.medKit = resolveMedKit(medKitId)
    }

    applyPlansTo(entity, resolveUser)
}

/**
 * Сводит коллекцию планов сущности к тому, что в домене: исчезнувшие удаляются, общие
 * получают новое количество, недостающие создаются.
 */
private fun Drug.applyPlansTo(entity: DrugData, resolveUser: (UUID) -> UserData) {
    val desired = plans.associateBy { it.userId }

    entity.treatmentPlans.removeIf { it.planKey.userId !in desired }
    entity.treatmentPlans.forEach { row ->
        desired[row.planKey.userId]?.let { row.plannedAmount = it.plannedAmount.amount }
    }

    val stored = entity.treatmentPlans.map { it.planKey.userId }.toSet()
    desired.values
        .filter { it.userId !in stored }
        .forEach { plan ->
            entity.treatmentPlans.add(
                TreatmentPlanData(
                    planKey = TreatmentPlanKey(plan.userId, entity.id),
                    userData = resolveUser(plan.userId),
                    drugData = entity,
                    plannedAmount = plan.plannedAmount.amount
                )
            )
        }
}

/** Новая строка под только что заведённый препарат. Планов у него ещё нет. */
internal fun Drug.toNewEntity(
    medKit: MedKitData,
    unit: QuantityUnitData,
    form: FormTypeData?
): DrugData = DrugData(
    id = id,
    name = name,
    quantity = quantity.amount,
    quantityUnit = unit,
    formType = form,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKit = medKit
)
