package org.kert0n.medappserver.services.aggregate

import kotlin.uuid.Uuid
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.RootLock
import org.kert0n.medappserver.domain.NotAMember
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Удерживает право на работу с аптечкой до конца внешней транзакции.
 *
 * Это не загрузчик агрегата и не capability в памяти: успешный вызов означает только то, что
 * PostgreSQL уже удерживает корневые строки. Какое состояние после этого загрузить и какую
 * команду выполнить, решает сценарный сервис, которому принадлежит вся операция.
 */
@Service
class MedKitAccessService(private val medKits: MedKitStore) {

    /** Обычная работа с содержимым: membership и корень не исчезнут до конца транзакции. */
    @Transactional(propagation = MANDATORY)
    fun holdContentAccess(medKitIds: Set<Uuid>, userId: Uuid) =
        hold(medKitIds, userId, RootLock.SHARED)

    /** Membership, удаление и перенос: состав и место содержимого меняются последовательно. */
    @Transactional(propagation = MANDATORY)
    fun holdLifecycleAccess(medKitIds: Set<Uuid>, userId: Uuid) =
        hold(medKitIds, userId, RootLock.EXCLUSIVE)

    /**
     * Два запроса, и второй не избыточен.
     *
     * Блокирующий `SELECT` берёт снимок до того, как встанет в очередь за корнем. Параллельный
     * выход удаляет строку `user_med_kits`, но саму строку `med_kits` не трогает, поэтому после
     * его коммита EvalPlanQual не срабатывает: перепроверять Postgres будет только тогда, когда
     * изменилась заблокированная строка. Присоединённое membership так и вернётся из устаревшего
     * снимка, и команда решит, что доступ есть.
     *
     * Второй запрос — новый statement, а значит и новый снимок: он уже видит коммит, которого
     * ждали корни. Убрать его — вернуть заведению брони нарушение внешнего ключа под гонкой;
     * поймано это поведенчески в `CommandAccessRaceTest`, а не по форме кода.
     *
     * Дальше membership исчезнуть не может: удаляют его только выход и глобальное удаление, а оба
     * берут корень исключительно и потому ждут нас.
     */
    private fun hold(medKitIds: Set<Uuid>, userId: Uuid, lock: RootLock) {
        val locked = medKits.lockAccessible(medKitIds, userId, lock)
        if (locked != medKitIds || medKits.accessible(medKitIds, userId) != medKitIds) throw NotAMember()
    }
}
