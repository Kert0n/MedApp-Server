package org.kert0n.medappserver.domain.drug

import java.util.UUID
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.model.Drug as DrugEntity
import org.kert0n.medappserver.db.model.TreatmentPlan as TreatmentPlanEntity

/**
 * Перенос состояния между доменом и отображением.
 *
 * Здесь и только здесь два представления препарата встречаются. Цена разделения — вся эта
 * файловая единица: каждое поле упомянуто дважды, и забытая строка означает поле, которое
 * молча не сохранится.
 *
 * SQL по-прежнему делает Hibernate: обратная запись идёт в **управляемую** сущность, а
 * добавление, изменение и удаление планов выражаются через её коллекцию, за которой стоят
 * каскад и `orphanRemoval`.
 */
fun DrugEntity.toDomain(): Drug = Drug.fromStored(
    id = id,
    medKitId = medKit.id,
    name = name,
    quantity = quantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    plans = treatmentPlans.map { TreatmentPlan(it.planKey.userId, it.plannedAmount) }
)

/**
 * Записывает состояние домена в сущность.
 *
 * Резолверы нужны потому, что домен знает только идентификаторы, а строкам планов нужен
 * `User`, и переезд требует `MedKit`. Достать их может лишь тот, у кого есть репозитории, —
 * поэтому загрузка приходит параметром, а не прячется внутри.
 */
fun Drug.applyTo(
    entity: DrugEntity,
    resolveUser: (UUID) -> User,
    resolveMedKit: (UUID) -> MedKit
) {
    entity.name = name
    entity.quantity = quantity
    entity.quantityUnit = quantityUnit
    entity.formType = formType
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
private fun Drug.applyPlansTo(entity: DrugEntity, resolveUser: (UUID) -> User) {
    val desired = plans.associateBy { it.userId }

    entity.treatmentPlans.removeIf { it.planKey.userId !in desired }
    entity.treatmentPlans.forEach { row ->
        desired[row.planKey.userId]?.let { row.plannedAmount = it.plannedAmount }
    }

    val stored = entity.treatmentPlans.map { it.planKey.userId }.toSet()
    desired.values
        .filter { it.userId !in stored }
        .forEach { plan ->
            entity.treatmentPlans.add(
                TreatmentPlanEntity(
                    planKey = TreatmentPlanKey(plan.userId, entity.id),
                    user = resolveUser(plan.userId),
                    drug = entity,
                    plannedAmount = plan.plannedAmount
                )
            )
        }
}

/** Новая сущность под только что созданный препарат. Планов у него ещё нет. */
fun Drug.toNewEntity(medKit: MedKit): DrugEntity = DrugEntity(
    id = id,
    name = name,
    quantity = quantity,
    quantityUnit = quantityUnit,
    formType = formType,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKit = medKit
)
