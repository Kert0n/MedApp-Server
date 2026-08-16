package org.kert0n.medappserver.queryplan

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.query.CatalogueQueryService
import org.kert0n.medappserver.application.query.DrugQueryService
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.application.service.MedKitAccessService
import org.kert0n.medappserver.application.service.MedKitService
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.security.SecurityService
import java.lang.reflect.Modifier
import kotlin.test.assertEquals

/** A new public DB-facing method must be assigned to a measured scenario. */
class PublicDbSurfaceRegistryTest {
    @Test
    fun `all public application DB methods have scenario owners`() {
        val expected = mapOf(
            DrugOrchestrator::class.java to setOf("create", "patch", "consume", "delete", "move"),
            TreatmentPlanOrchestrator::class.java to setOf("create", "patch", "delete"),
            IntakeOrchestrator::class.java to setOf("record"),
            MedKitOrchestrator::class.java to setOf("create", "createInvitation", "join", "leave", "delete"),
            DrugService::class.java to setOf(
                "lockAllByMedKitIds", "moveAll", "medKitId", "create", "patch", "consume", "delete",
                "move", "createPlan", "patchPlan", "deletePlan", "applyIntake"
            ),
            MedKitAccessService::class.java to setOf("lockAccessible", "memberIds"),
            MedKitService::class.java to setOf(
                "create", "lockAccessible", "join", "prepareLeave", "completeLeave", "deleteLocked"
            ),
            CatalogueQueryService::class.java to setOf("search", "get"),
            DrugQueryService::class.java to setOf("getAccessible"),
            TreatmentPlanQueryService::class.java to setOf("listForUser", "getForUser"),
            MedKitQueryService::class.java to setOf("listForUser", "getContent", "getUserSnapshot"),
            UserService::class.java to setOf("registerNewUser", "loadUserByUsername", "findById"),
            VidalDrugService::class.java to setOf("fuzzySearch", "findById")
        )

        expected.forEach { (type, registered) ->
            val actual = type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .mapTo(sortedSetOf()) { it.name }
            assertEquals(registered.toSortedSet(), actual, "Update SQL scenario registry for ${type.simpleName}")
        }
    }

    @Test
    fun `all public security methods belong to explicit zero SQL scenarios`() {
        val registered = setOf(
            "generateKey", "check", "hashPassword", "hashToken", "secretsMatch", "generateToken",
            "validateRequest", "isLoginAllowed", "recordLoginAttempt", "registerIncrease"
        )
        val actual = SecurityService::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .mapTo(sortedSetOf()) { it.name }
        assertEquals(registered.toSortedSet(), actual)
    }
}
