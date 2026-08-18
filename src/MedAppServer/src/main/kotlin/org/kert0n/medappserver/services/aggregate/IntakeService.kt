package org.kert0n.medappserver.services.aggregate

import org.kert0n.medappserver.domain.ConflictingSync
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.db.store.IntakeStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату приёма.
 *
 * Отвечает на вопрос «можно ли делать операцию» — и отвечает **сам**: кто он такой и как себя
 * проверить, знает он, а не тот, кто спрашивает. Вызывающему остаётся спросить и отдать
 * операцию дальше.
 *
 * До журнала отсюда и только отсюда — как и до любого другого хранилища. Раньше он торчал
 * прямо в прикладном сервисе, и тот сам решал, повтор это или нет.
 *
 * Идемпотентность для домена не новость — новость только механизм, которым она обеспечена.
 */
@Service
class IntakeService(private val journal: IntakeStore) {

    private val logger = LoggerFactory.getLogger(IntakeService::class.java)

    /**
     * `true` — этот запрос уже выполнен, применять нечего.
     *
     * Тот же идентификатор с другим содержимым запросом не является: одно из двух не то, и
     * решить, какое именно, может только клиент.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun alreadyApplied(intake: Intake): Boolean {
        val previous = journal.find(intake.id) ?: return false
        if (!intake.isRepeatOf(previous)) throw ConflictingSync()
        logger.debug("Sync {} is a repeat", intake.id)
        return true
    }

    /** Отмечает выполненное. Запись появится только после коммита — этим занят сам журнал. */
    @Transactional(propagation = MANDATORY)
    fun record(intake: Intake) = journal.record(intake)
}
