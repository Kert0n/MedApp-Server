package org.kert0n.medappserver.services.orchestrator

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Переезд упаковок в другую аптечку.
 *
 * Правило одно на оба случая — поштучный переезд и массовый при удалении аптечки: состав
 * целевой удерживается до коммита, а брони тех, кто её не видит, снимаются. Нужно оно двум
 * фасадам, и потому живёт здесь, а не у одного из них.
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
     * Переезд одной пачки.
     *
     * Правило целиком: **назначение переживает переезд, если человек допущен к целевому
     * хранилищу, и снимается, если не допущен** — см. `Reservation.survivesRelocationTo`.
     * Коробку переставили на другую полку; для того, кто к полке допущен, ничего не изменилось.
     *
     * Обе половины видны здесь: непереживших снимает `dropOnDrugExcept`, а уцелевшие едут за
     * пачкой — их `med_kit_id` обязан оказаться новым.
     *
     * Состав аптечки версией не удерживается: она отвечает за состояние своей сущности, а не
     * за права. Если состав изменится под нами, оставшуюся без членства бронь уберёт ключ.
     */
    @Transactional(propagation = MANDATORY)
    fun moveOne(drugId: UUID, target: MedKit, userId: UUID, expectedVersion: Long): Drug {
        val moved = drugService.moveTo(drugId, target.id, userId, expectedVersion)
        reservationService.dropOnDrugExcept(drugId, target.members)
        return moved
    }

    /**
     * Переезд всего содержимого аптечки.
     *
     * То же правило, что у одной пачки, и та же пара половин — но двумя запросами: аптечка со
     * ста пачками не должна стоить ста загрузок.
     *
     * Состав здесь тоже не удерживается версией — по той же причине, что и у одной пачки.
     */
    @Transactional(propagation = MANDATORY)
    fun moveAll(sourceMedKitId: UUID, target: MedKit) {
        // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
        reservationService.dropInMedKitExcept(sourceMedKitId, target.members)
        drugService.moveAllToMedKit(sourceMedKitId, target.id)
    }
}
