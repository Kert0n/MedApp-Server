package org.kert0n.medappserver.services.orchestrators

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.services.models.PlanSnapshot
import org.kert0n.medappserver.services.security.hashToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class IntakeService(
    private val treatmentPlans: TreatmentPlanService,
    @Qualifier("intakeResultsCache") private val intakeResultsCache: Cache<String, IntakeOutcome>
) {

    private val logger = LoggerFactory.getLogger(IntakeService::class.java)
    private val inFlight = ConcurrentHashMap<String, Any>()

    fun record(
        userId: UUID,
        drugId: UUID,
        quantityConsumed: BigDecimal,
        intakeId: UUID
    ): IntakeOutcome {
        val key = hashToken("$userId$intakeId")
        val monitor = inFlight.computeIfAbsent(key) { Any() }
        try {
            synchronized(monitor) {
                intakeResultsCache.getOrNull(key)?.let { seen ->
                    if (!seen.matches(drugId, quantityConsumed)) {
                        throw ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Intake ID was already used with another payload"
                        )
                    }
                    logger.debug("Returning cached intake result")
                    return seen
                }

                val plan = treatmentPlans.applyIntake(userId, drugId, quantityConsumed)
                val outcome = IntakeOutcome(drugId, quantityConsumed, plan)
                intakeResultsCache.put(key, outcome)
                return outcome
            }
        } finally {
            inFlight.remove(key, monitor)
        }
    }
}

data class IntakeOutcome(
    val drugId: UUID,
    val quantityConsumed: BigDecimal,
    val plan: PlanSnapshot?
) {
    fun matches(drugId: UUID, quantityConsumed: BigDecimal): Boolean =
        this.drugId == drugId && this.quantityConsumed.compareTo(quantityConsumed) == 0
}
