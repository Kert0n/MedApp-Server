package org.kert0n.medappserver.application.orchestrator

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.application.model.IntakeCacheEntry
import org.kert0n.medappserver.application.model.IntakePayload
import org.kert0n.medappserver.application.model.IntakeResult
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.domain.error.IntakeConflict
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class IntakeOrchestrator(
    transactionManager: PlatformTransactionManager,
    private val drugService: DrugService,
    @Qualifier("intakeRecordsCache") private val cache: Cache<String, IntakeCacheEntry>
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val locks = Array(256) { Any() }

    fun record(userId: UUID, intakeId: UUID, payload: IntakePayload): IntakeResult {
        val key = "$userId:$intakeId"
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        return synchronized(lock) {
            cache.getOrNull(key)?.let { stored ->
                if (stored.payload != payload) throw IntakeConflict()
                return@synchronized stored.result
            }

            val result = requireNotNull(
                transaction.execute {
                    drugService.applyIntake(userId, payload.drugId, payload.normalizedQuantity)
                }
            )
            cache.put(key, IntakeCacheEntry(payload, result))
            result
        }
    }
}
