package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Заведение упаковки в аптечке.
 *
 * Правило взаимодействия: **упаковка не существует сама по себе — она всегда в аптечке, и
 * только в той, к которой у заводящего есть доступ.** Доступ даёт чтение аптечки, оно же и
 * единственная проверка.
 *
 * Читает чужой агрегат здесь, а не в `DrugService`: сервису упаковки знать про аптечку не
 * положено. Правила самой упаковки остаются у него и сюда не переезжают.
 *
 * Оркестратор: домен на входе и на выходе, про клиента не знает.
 */
@Service
class DrugPlacement(
    private val drugService: DrugService,
    private val medKitService: MedKitService
) {

    /** Основная форма: аптечка уже прочитана, значит доступ к ней доказан. */
    @Transactional(propagation = MANDATORY)
    fun place(request: NewDrug, medKit: MedKit): Drug = drugService.create(request, medKit)

    /** По идентификатору — то же самое плюс чтение аптечки. */
    @Transactional(propagation = MANDATORY)
    fun place(request: NewDrug, medKitId: Uuid, userId: Uuid): Drug =
        place(request, medKitService.get(medKitId, userId))
}
