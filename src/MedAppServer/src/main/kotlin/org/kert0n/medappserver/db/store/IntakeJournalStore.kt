package org.kert0n.medappserver.db.store

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.IntakeJournal
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Журнал синхронизаций поверх кеша.
 *
 * Лежит среди хранилищ, потому что это и есть хранилище агрегата — граница, за которой сервис
 * не знает, чем всё держится. То, что за ней память процесса, а не таблица, — решение о
 * приватности, а не о слое: приём слишком личное, чтобы копить его строками (см. `Intake`).
 *
 * Отсюда и цена, названная в описании операции: после перезапуска сервера повтор старого
 * запроса журналу неизвестен и спишет второй раз.
 */
@Component
class IntakeJournalStore(private val journal: Cache<UUID, Intake>) : IntakeJournal {

    override fun find(id: UUID): Intake? = journal.getOrNull(id)

    /**
     * Запись появляется только после успешного коммита.
     *
     * Кеш не участвует в транзакции, поэтому запись «по ходу» пережила бы откат — и повтор
     * получил бы подтверждение того, чего в базе нет. Ошибиться так легко: откат чаще всего
     * приходит не изнутри команды, а с коммита, который случается уже **после** возврата из
     * сервиса, — `try/catch` вокруг команды его не увидит.
     *
     * Вычищать запись при откате вместо отложенной вставки тоже можно, но тогда одновременный
     * дубликат успевает увидеть чужую незакоммиченную запись и получить подтверждение раньше
     * времени. Здесь он её не увидит: он либо проиграет на версии и повторится, либо придёт,
     * когда первый уже закоммитился.
     */
    override fun record(intake: Intake) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            journal[intake.id] = intake
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    journal[intake.id] = intake
                }
            }
        )
    }
}
