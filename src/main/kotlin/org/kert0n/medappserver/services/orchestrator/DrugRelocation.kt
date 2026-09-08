package org.kert0n.medappserver.services.orchestrator

import kotlin.uuid.Uuid
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
     * Переезд одной пачки по идентификатору цели — вход, который сам обеспечивает протокол.
     *
     * Оба корня берутся исключительно и в порядке UUID: пока переезд идёт, состав участников
     * обеих аптечек не меняется, и решение «чью бронь сохранить» не устаревает между проверкой
     * и записью. Блокировка стоит здесь, а не у фасада, потому что она часть сценария, а не
     * подробность одного из его вызывающих.
     */
    @Transactional(propagation = MANDATORY)
    fun moveOne(drug: Drug, targetMedKitId: Uuid, userId: Uuid, stated: Long): Drug {
        val locked = medKitService.lock(setOf(drug.medKitId, targetMedKitId), userId).associateBy { it.id }
        return moveOne(drug, locked.getValue(targetMedKitId), stated)
    }

    /**
     * Переезд одной пачки: сначала снять брони тех, кто цель не видит, потом переставить.
     *
     * Доступ к целевой аптечке проверен её чтением, а тех, чьи брони можно сохранить,
     * хранилище определяет по актуальным строкам членства.
     */
    @Transactional(propagation = MANDATORY)
    fun moveOne(drug: Drug, target: MedKit, stated: Long): Drug {
        // Порядок важен, как и в массовом переезде: сначала снять брони тех, кто цель не
        // видит, и только потом двигать пачку. Иначе `ON UPDATE CASCADE` потащит их
        // `med_kit_id` в целевую аптечку, и ключ членства отвергнет весь переезд.
        reservationService.dropOnDrugExcept(drug, target)
        return drugService.moveTo(drug, target, stated)
    }

    /**
     * Переезд всего содержимого аптечки.
     *
     * То же правило, что у одной пачки, но двумя запросами: аптечка со ста пачками не должна
     * стоить ста загрузок.
     *
     * Формы по идентификаторам здесь нет намеренно: она брала бы корни обычными чтениями и
     * выполняла тот же сценарий без протокола. Обе аптечки приходят уже заблокированными от
     * того, кто удаляет исходную, — ему они нужны и для самого удаления.
     */
    @Transactional(propagation = MANDATORY)
    fun moveAll(source: MedKit, target: MedKit) {
        // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
        reservationService.dropInMedKitExcept(source, target)
        drugService.moveAll(source, target)
    }
}
