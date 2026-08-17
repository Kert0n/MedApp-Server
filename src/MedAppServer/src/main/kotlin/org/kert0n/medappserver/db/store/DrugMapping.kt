package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit

/**
 * Перенос состояния упаковки между доменом и отображением.
 *
 * Живёт в слое хранения: знать про строки таблиц — работа persistence, и зависимость направлена
 * только сюда. Цена разделения видна здесь же — каждое поле названо дважды, и забытая строка
 * означает поле, которое молча не сохранится. SQL остаётся за Hibernate: запись идёт в
 * **управляемую** сущность.
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
        version = version
    )
}

/** Версия не переносится обратно: ею распоряжается Hibernate, присваивание ей запрещено. */
internal fun Drug.applyTo(
    entity: DrugData,
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
}

/** Новая строка под только что заведённую упаковку. */
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
