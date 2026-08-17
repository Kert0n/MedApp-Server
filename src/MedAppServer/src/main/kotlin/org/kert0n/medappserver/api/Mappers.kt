package org.kert0n.medappserver.api

import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation

/**
 * Перевод доменных значений в публичный контракт.
 *
 * DTO собирается из того же состояния, по которому агрегат принимает решения, — отдельных форм
 * чтения нет. Единица и форма отдаются парой «идентификатор и имя»: первый прислать обратно при
 * правке, второе — нарисовать карточку без второго запроса.
 *
 * Упаковка идёт вместе с заявленными на неё бронями, доменными объектами: сама она про них не
 * знает, а тип-носитель между доменом и DTO незачем. Сумма может превышать содержимое пачки —
 * законное состояние.
 */
fun Drug.toDto(reservations: List<Reservation>): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity.amount,
    reservedQuantity = reservations.sumOf { it.amount.amount },
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

fun Reservation.toDto(): ReservationDTO = ReservationDTO(
    drugId = drugId,
    amount = amount.amount
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

/**
 * Сводка аптечки для списка.
 *
 * Счётчики — по тому, что уже на руках: участники в агрегате, число пачек от вызывающего,
 * который их всё равно читал. Отдельный запрос ради двух чисел незачем.
 */
fun MedKit.toSummaryDto(drugCount: Int): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = members.size.toLong(),
    drugCount = drugCount.toLong()
)
