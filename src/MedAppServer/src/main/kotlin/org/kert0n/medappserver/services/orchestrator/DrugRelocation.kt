package org.kert0n.medappserver.services.orchestrator

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Переезд упаковок в другую аптечку.
 *
 * Правило одно на оба случая — поштучный переезд и массовый при удалении аптечки: **назначение
 * переживает переезд, если человек допущен к целевому хранилищу, и снимается, если не допущен.**
 * Коробку переставили на другую полку; для того, кто к полке допущен, ничего не изменилось.
 *
 * Нужно оно двум фасадам, и потому живёт здесь, а не у одного из них.
 *
 * Оркестратор: домен на входе и на выходе, про клиента не знает.
 */
@Service
class DrugRelocation(
    private val drugService: DrugService,
    private val medKitService: MedKitService,
    private val reservationService: ReservationService
) {

    /**
     * Переезд одной пачки: сначала переставить, потом снять брони тех, кто цель не видит.
     *
     * Состав целевой аптечки берётся тот, что прочитал вызывающий: правило смотрит на её
     * участников, и решение принимается по ним.
     */
    /**
     * По идентификаторам — то же самое плюс два чтения.
     *
     * Чужой агрегат читает оркестратор, а не сервис упаковки: тому знать про аптечку не
     * положено. Оба чтения скоуплены вызывающим, и оба — проверка доступа.
     */
    @Transactional(propagation = MANDATORY)
    fun moveOne(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug =
        moveOne(drugService.get(drugId, userId), medKitService.get(targetMedKitId, userId))

    @Transactional(propagation = MANDATORY)
    fun moveOne(drug: Drug, target: MedKit): Drug {
        // Порядок важен, как и в массовом переезде: сначала снять брони тех, кто цель не
        // видит, и только потом двигать пачку. Иначе `ON UPDATE CASCADE` потащит их
        // `med_kit_id` в целевую аптечку, и ключ членства отвергнет весь переезд.
        reservationService.dropOnDrugExcept(drug, target.members)
        return drugService.moveTo(drug, target)
    }

    /**
     * Переезд всего содержимого аптечки.
     *
     * То же правило, что у одной пачки, но двумя запросами: аптечка со ста пачками не должна
     * стоить ста загрузок.
     */
    /** По идентификаторам — то же самое плюс два чтения, и оба проверяют доступ. */
    @Transactional(propagation = MANDATORY)
    fun moveAll(sourceMedKitId: UUID, targetMedKitId: UUID, userId: UUID) =
        moveAll(medKitService.get(sourceMedKitId, userId), medKitService.get(targetMedKitId, userId))

    @Transactional(propagation = MANDATORY)
    fun moveAll(source: MedKit, target: MedKit) {
        // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
        reservationService.dropInMedKitExcept(source, target.members)
        drugService.moveAll(source, target)
    }
}
