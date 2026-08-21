package org.kert0n.medappserver.db.store

import org.kert0n.medappserver.db.model.DrugData
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
 * означает поле, которое молча не сохранится.
 *
 * Ссылки переносятся идентификаторами: они у домена уже есть, и поднимать ради них чужие строки
 * значило бы ходить в соседний агрегат мимо его сервиса.
 */
internal fun QuantityUnitData.toDomain(): QuantityUnit = QuantityUnit(id, name)

internal fun FormTypeData.toDomain(): FormType = FormType(id, name)

internal fun DrugData.toDomain(): Drug {
    // Имя единицы нужно только представлению, но пока `DrugDTO` его отдаёт, читать приходится
    // и здесь. Уйдёт вместе с именами словарей из контракта.
    val unit = quantityUnit?.toDomain() ?: error("Упаковка $id прочитана без единицы измерения")
    return Drug(
        id = id,
        medKitId = medKitId,
        name = name,
        quantity = Quantity(quantity, unit),
        formType = formType?.toDomain(),
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}

internal fun Drug.applyTo(entity: DrugData) {
    entity.name = name
    entity.quantity = quantity.amount
    entity.quantityUnitId = quantity.unit.id
    entity.formTypeId = formType?.id
    entity.category = category
    entity.manufacturer = manufacturer
    entity.country = country
    entity.description = description
    entity.medKitId = medKitId
}

/** Новая строка под только что заведённую упаковку. */
internal fun Drug.toNewEntity(): DrugData = DrugData(
    id = id,
    name = name,
    quantity = quantity.amount,
    quantityUnitId = quantity.unit.id,
    formTypeId = formType?.id,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKitId
)
