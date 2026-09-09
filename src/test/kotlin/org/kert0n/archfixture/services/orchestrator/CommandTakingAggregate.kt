package org.kert0n.archfixture.services.orchestrator

import org.kert0n.medappserver.domain.Drug

/**
 * Публичная команда, принимающая снимок чтения вместо идентификатора.
 *
 * Ровно то нарушение, которое пропускала прежняя текстовая проверка: она искала имя типа перед
 * двоеточием, а в Kotlin тип стоит после него.
 */
class CommandTakingAggregate {
    fun place(drug: Drug, stated: Long): Long = drug.version + stated
}
