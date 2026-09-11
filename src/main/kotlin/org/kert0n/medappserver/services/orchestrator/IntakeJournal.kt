package org.kert0n.medappserver.services.orchestrator

import com.sksamuel.aedile.core.Cache
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Intake
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Журнал расхода: какие приёмы уже применены, по придуманному клиентом идентификатору.
 *
 * Один на синхронизацию и приём, и это не совпадение, а тождество: синхронизация со съеденным
 * `x` и приём `x` той же пачки под одним идентификатором — одно и то же списание, и второе из
 * них — повтор первого.
 *
 * Часть сценария, а не сценарий: `@Component`, а не `@Service`. Своего входа у журнала нет, и
 * то, что им пользуются два сценария, не делает один из них вложенным в другой.
 *
 * Живёт в памяти процесса — почему не в базе и чем это ограничено, записано у
 * `CacheService.syncJournalCache`.
 */
@Component
class IntakeJournal(private val syncJournalCache: Cache<Uuid, Intake>) {

    private val logger = LoggerFactory.getLogger(IntakeJournal::class.java)

    /**
     * Повтор того же запроса ничего не делает.
     *
     * Это не приём кеширования, а тождество приёма: с таким идентификатором он либо записан,
     * либо нет. Тот же идентификатор с другим содержимым — не повтор, а другая команда, и
     * подтверждать её как выполненную нельзя.
     */
    fun alreadyApplied(intake: Intake): Boolean {
        val applied = syncJournalCache.getOrNull(intake.id) ?: return false
        if (!applied.sameAs(intake)) throw ConflictingSync()

        logger.debug("Intake {} already applied, repeating the answer", intake.id)
        return true
    }

    /**
     * Журнал пишется после коммита.
     *
     * Записать по ходу команды значило бы пережить откат: повтор получил бы подтверждение того,
     * чего в базе нет, а клиент вычистил бы офлайн-очередь.
     */
    fun rememberAfterCommit(intake: Intake) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    syncJournalCache.put(intake.id, intake)
                }
            }
        )
    }
}

/** Тот же идентификатор с другим содержимым: это не повтор, а другая команда. */
class ConflictingSync : RuntimeException("A different request was already applied under this identifier")
