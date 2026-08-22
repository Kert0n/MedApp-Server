package org.kert0n.medappserver.api

import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationSnapshot

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
fun Drug.toDto(): DrugDTO = DrugDTO(
    id = id,
    name = name,
    quantity = quantity.amount,
    quantityUnitId = quantity.unit.id,
    formTypeId = formType?.id,
    category = category,
    manufacturer = manufacturer,
    country = country,
    description = description,
    medKitId = medKitId,
    version = version
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
    category = category,
    quantityUnitId = quantityUnit?.id,
    manufacturer = manufacturer,
    country = country,
    description = description
)

/**
 * Снимок упаковки: сама пачка и то, что на неё заявлено.
 *
 * Своя доля отделена от общей суммы: одну показывают владельцу, по другой судят, разобрана ли
 * пачка. Упаковка про брони не знает — их приносит вызывающий.
 */
fun Drug.toSnapshot(reservations: ReservationSnapshot): DrugSnapshotDTO =
    DrugSnapshotDTO(drug = toDto(), reservations = reservations.toDto())

/** Картина броней в своей форме ответа: собирает её она сама, а не тот, кому она понадобилась. */
fun ReservationSnapshot.toDto(): ReservationsDTO = ReservationsDTO(
    total = total,
    mine = mine,
    version = version
)

/** Аптечка с содержимым: число участников она знает сама, упаковки приносит вызывающий. */
fun MedKit.toDto(drugs: Set<DrugSnapshotDTO>): MedKitDTO = MedKitDTO(
    id = id,
    userCount = members.size.toLong(),
    drugs = drugs
)

/**
 * Набор упаковок вместе с бронями на них.
 *
 * Группировка живёт здесь, а не у вызывающего: сборка ответа — работа этого файла, и
 * повторять её в каждом сценарии значило бы держать её в трёх местах сразу.
 *
 * Брони приходят одним списком на весь набор — тем самым, что читается одним запросом.
 */
fun List<Drug>.toSnapshots(reservations: Map<Uuid, ReservationSnapshot>): List<DrugSnapshotDTO> =
    map { it.toSnapshot(reservations.getValue(it.id)) }

/** Справка: по каким аптечкам и каким пачкам клиент может свериться, что ничего не исчезло. */
fun List<MedKit>.toSummaryDto(accessiblePackages: List<Drug>): Set<MedKitSummaryDTO> {
    val perMedKit = accessiblePackages.groupBy { it.medKitId }
    return map { it.toSummaryDto(perMedKit[it.id].orEmpty().map { drug -> drug.id }.toSet()) }.toSet()
}

/** Аптечки вместе с содержимым: пачки разбираются по аптечкам, к которым принадлежат. */
fun List<MedKit>.toDto(accessibleDrugs: List<DrugSnapshotDTO>): Set<MedKitDTO> {
    val perMedKit = accessibleDrugs.groupBy { it.drug.medKitId }
    return map { it.toDto(perMedKit[it.id].orEmpty().toSet()) }.toSet()
}

fun QuantityUnit.toDto(): VocabularyEntryDTO = VocabularyEntryDTO(id = id, name = name)

fun FormType.toDto(): VocabularyEntryDTO = VocabularyEntryDTO(id = id, name = name)

/**
 * Сводка аптечки для списка.
 *
 * Счётчики — по тому, что уже на руках: участники в агрегате, число пачек от вызывающего,
 * который их всё равно читал. Отдельный запрос ради двух чисел незачем.
 */
fun MedKit.toSummaryDto(drugIds: Set<Uuid>): MedKitSummaryDTO = MedKitSummaryDTO(
    id = id,
    userCount = members.size.toLong(),
    drugIds = drugIds
)
