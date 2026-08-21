package org.kert0n.medappserver.services.orchestrator

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.DrugService
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
    private val reservationService: ReservationService
) {

    /**
     * Переезд одной пачки: сначала переставить, потом снять брони тех, кто цель не видит.
     *
     * Состав целевой аптечки берётся тот, что прочитал вызывающий: правило смотрит на её
     * участников, и решение принимается по ним.
     */
    @Transactional(propagation = MANDATORY)
    fun moveOne(drugId: UUID, target: MedKit, userId: UUID): Drug {
        val moved = drugService.moveTo(drugId, target.id, userId)
        reservationService.dropOnDrugExcept(drugId, target.members)
        return moved
    }

    /**
     * Переезд всего содержимого аптечки.
     *
     * То же правило, что у одной пачки, но двумя запросами: аптечка со ста пачками не должна
     * стоить ста загрузок.
     */
    @Transactional(propagation = MANDATORY)
    fun moveAll(sourceMedKitId: UUID, target: MedKit) {
        // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
        reservationService.dropInMedKitExcept(sourceMedKitId, target.members)
        drugService.moveAllToMedKit(sourceMedKitId, target.id)
    }
}
