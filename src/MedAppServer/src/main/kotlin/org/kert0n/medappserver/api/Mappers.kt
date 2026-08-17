package org.kert0n.medappserver.api

import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.MedKitOverview
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation

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
/**
 * Упаковка в ответе — вместе с бронями, которые на неё заявлены.
 *
 * Брони передаются как есть, доменными объектами: сама упаковка про них не знает, а заводить
 * между доменом и DTO ещё один тип-носитель незачем — `data class` затем и нужен, чтобы его
 * свободно передавать. Вызывающий отдаёт брони именно этой пачки; сумма может превышать её
 * содержимое, и это законное состояние.
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

fun MedKitOverview.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = memberCount,
    drugCount = drugCount
)
