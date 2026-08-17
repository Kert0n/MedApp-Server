package org.kert0n.medappserver.db.store

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.IntakeJournal
import org.springframework.stereotype.Component

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

    override fun record(intake: Intake) {
        journal[intake.id] = intake
    }
}
