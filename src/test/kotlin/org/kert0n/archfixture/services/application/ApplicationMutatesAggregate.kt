package org.kert0n.archfixture.services.application

import org.kert0n.medappserver.services.aggregate.DrugService

/** Фасад, дотянувшийся до сервиса агрегата ради записи мимо сценария. */
class ApplicationMutatesAggregate(private val drugs: DrugService) {
    fun destroy(drugId: org.kert0n.medappserver.domain.Drug, stated: Long) = drugs.delete(drugId, stated)
}
