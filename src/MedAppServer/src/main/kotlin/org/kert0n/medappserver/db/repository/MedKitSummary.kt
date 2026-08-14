package org.kert0n.medappserver.db.repository

import java.util.UUID

/**
 * Аптечка без содержимого: сколько в ней участников и сколько препаратов.
 *
 * Проекция запроса [MedKitRepository.findMedKitSummariesByUserId] — считать это агрегатами в
 * базе дешевле, чем грузить обе коллекции ради двух чисел.
 *
 * Тип принадлежит слою хранилища, а не api, и это принципиально. Раньше конструкторное
 * выражение JPQL называло `org.kert0n.medappserver.api.MedKitSummaryDTO` — то есть
 * репозиторий знал HTTP-контракт, причём **внутри строки**, которую компилятор не проверяет:
 * переименование DTO ломало запрос при старте приложения, а не при сборке. Плюс объект ехал
 * от репозитория до ответа вообще без маппинга, и поменять форму ответа, не тронув запрос,
 * было нельзя.
 *
 * Наружу его переводит `MedKitSummary.toDto()` в `api/Mappers.kt`.
 */
data class MedKitSummary(
    val id: UUID,
    val userCount: Long,
    val drugCount: Long
)
