package org.kert0n.medappserver.services.orchestrator

import java.util.UUID
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
     * Состав аптечки здесь — основание решения, а не то, что меняется: `requireUnchanged`
     * требует, чтобы он дожил до коммита. Иначе вышедший в этот момент участник сохранил бы
     * бронь на пачку, которую больше не видит.
     */
    @Transactional
    fun moveOne(drugId: UUID, target: MedKit, userId: UUID, expectedVersion: Long): Drug {
        medKitService.requireUnchanged(target)
        val moved = drugService.moveTo(drugId, target.id, userId, expectedVersion)
        reservationService.dropOnDrugExcept(drugId, target.members)
        return moved
    }

    /**
     * Переезд всего содержимого аптечки.
     *
     * То же правило, но двумя запросами: аптечка со ста пачками не должна стоить ста загрузок.
     *
     * Состав требуется дважды, и это не перестраховка. **Замерено:** массовый `UPDATE` с
     * `clearAutomatically` очищает persistence context и уносит вместе с ним зарегистрированную
     * проверку версии — снял из сценария один этот запрос, и та же гонка стала отвергаться.
     * Поэтому состав перепроверяется после него, уже по свежему чтению; держится это на том,
     * что `requireUnchanged` сравнивает версию со снимком явно.
     */
    @Transactional
    fun moveAll(sourceMedKitId: UUID, target: MedKit) {
        medKitService.requireUnchanged(target)
        // Порядок важен: брони выбираются по исходной аптечке, пока упаковки ещё в ней.
        reservationService.dropInMedKitExcept(sourceMedKitId, target.members)
        drugService.moveAllToMedKit(sourceMedKitId, target.id)
        medKitService.requireUnchanged(target)
    }
}
