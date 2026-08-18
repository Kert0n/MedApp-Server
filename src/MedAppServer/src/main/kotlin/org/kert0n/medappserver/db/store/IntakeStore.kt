package org.kert0n.medappserver.db.store

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.domain.Intake
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Хранилище агрегата приёма — такое же, как остальные, только за ним кеш, а не таблица.
 *
 * Порта в домене под это нет намеренно: граница агрегата — само хранилище, а чем оно держится,
 * его дело. Интерфейс поверх одной реализации добавлял бы слой, ничего не разделяя, и уводил бы
 * решение о хранении в домен, которому оно не принадлежит.
 *
 * Кеш, а не таблица, — решение о приватности: приём слишком личное, чтобы копить его строками
 * ради технической задачи «не списать дважды» (см. `Intake`). Отсюда и цена, названная в
 * описании операции: после перезапуска сервера повтор старого запроса неизвестен и спишет
 * второй раз.
 */
@Component
class IntakeStore(private val journal: Cache<UUID, Intake>) {

    fun find(id: UUID): Intake? = journal.getOrNull(id)

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
    fun record(intake: Intake) {
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
