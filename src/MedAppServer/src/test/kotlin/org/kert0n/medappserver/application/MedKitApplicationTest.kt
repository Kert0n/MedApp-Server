package org.kert0n.medappserver.application

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.application.model.CreateDrugCommand
import org.kert0n.medappserver.application.model.CreateTreatmentPlanCommand
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.error.InvitationNotFound
import org.kert0n.medappserver.domain.error.InvalidMedKitTarget
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@PostgresIntegrationTest
class MedKitApplicationTest {
    @Autowired private lateinit var medKitOrchestrator: MedKitOrchestrator
    @Autowired private lateinit var drugOrchestrator: DrugOrchestrator
    @Autowired private lateinit var planOrchestrator: TreatmentPlanOrchestrator
    @Autowired private lateinit var queryService: MedKitQueryService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `create and queries return immutable medkit projections`() {
        val owner = user()
        val medKit = medKitOrchestrator.create(owner)
        val drug = drugOrchestrator.create(owner, drugCommand(medKit.id))
        planOrchestrator.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("3")))

        val summaries = queryService.listForUser(owner)
        val content = queryService.getContent(owner, medKit.id)
        val snapshot = queryService.getUserSnapshot(owner)

        assertEquals(1, summaries.size)
        assertEquals(1, summaries.single().drugCount)
        assertEquals(BigDecimal("3.000000"), content.drugs.single().plannedQuantity)
        assertEquals(content, snapshot.medKits.single())
    }

    @Test
    fun `invitation joins participant and repeated join is idempotent`() {
        val owner = user()
        val participant = user()
        val medKit = medKitOrchestrator.create(owner)
        val invitation = medKitOrchestrator.createInvitation(owner, medKit.id)

        medKitOrchestrator.join(participant, invitation.key)
        medKitOrchestrator.join(participant, invitation.key)

        assertEquals(1, count("user_med_kits", "med_kit_id = ? AND user_id = ?", medKit.id, participant))
        assertFailsWith<InvitationNotFound> {
            medKitOrchestrator.join(participant, "missing-key")
        }
    }

    @Test
    fun `non-last member leave deletes own plans in one lifecycle operation`() {
        val owner = user()
        val participant = user()
        val medKit = medKitOrchestrator.create(owner)
        val key = medKitOrchestrator.createInvitation(owner, medKit.id).key
        medKitOrchestrator.join(participant, key)
        val drug = drugOrchestrator.create(owner, drugCommand(medKit.id))
        planOrchestrator.create(participant, CreateTreatmentPlanCommand(drug.id, BigDecimal("2")))

        medKitOrchestrator.leave(participant, medKit.id)

        assertEquals(1, count("med_kits", "id = ?", medKit.id))
        assertEquals(0, count("user_med_kits", "med_kit_id = ? AND user_id = ?", medKit.id, participant))
        assertEquals(0, count("usings", "drug_id = ? AND user_id = ?", drug.id, participant))
    }

    @Test
    fun `last member leave relies on database cascades`() {
        val owner = user()
        val medKit = medKitOrchestrator.create(owner)
        val drug = drugOrchestrator.create(owner, drugCommand(medKit.id))
        planOrchestrator.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("2")))

        medKitOrchestrator.leave(owner, medKit.id)

        assertEquals(0, count("med_kits", "id = ?", medKit.id))
        assertEquals(0, count("user_drugs", "id = ?", drug.id))
        assertEquals(0, count("usings", "drug_id = ?", drug.id))
    }

    @Test
    fun `delete with transfer preserves only target member plans`() {
        val owner = user()
        val participant = user()
        val source = medKitOrchestrator.create(owner)
        val target = medKitOrchestrator.create(owner)
        medKitOrchestrator.join(
            participant,
            medKitOrchestrator.createInvitation(owner, source.id).key
        )
        val drug = drugOrchestrator.create(owner, drugCommand(source.id))
        planOrchestrator.create(owner, CreateTreatmentPlanCommand(drug.id, BigDecimal("3")))
        planOrchestrator.create(participant, CreateTreatmentPlanCommand(drug.id, BigDecimal("2")))

        medKitOrchestrator.delete(owner, source.id, target.id)

        assertEquals(0, count("med_kits", "id = ?", source.id))
        assertEquals(1, count("user_drugs", "id = ? AND med_kit_id = ?", drug.id, target.id))
        assertEquals(1, count("usings", "drug_id = ?", drug.id))
        assertEquals(1, count("usings", "drug_id = ? AND user_id = ?", drug.id, owner))
    }

    @Test
    fun `delete rejects transfer into itself before mutation`() {
        val owner = user()
        val medKit = medKitOrchestrator.create(owner)

        assertFailsWith<InvalidMedKitTarget> {
            medKitOrchestrator.delete(owner, medKit.id, medKit.id)
        }
        assertEquals(1, count("med_kits", "id = ?", medKit.id))
    }

    private fun user(): UUID = TransactionTemplate(transactionManager).execute {
        val user = userRepository.saveAndFlush(User(hashedKey = "user_${UUID.randomUUID()}"))
        user.id
    }!!

    private fun drugCommand(medKitId: UUID): CreateDrugCommand = CreateDrugCommand(
        medKitId = medKitId,
        name = "Drug",
        quantity = BigDecimal.TEN,
        quantityUnit = "tablet"
    )

    private fun count(table: String, predicate: String, vararg values: Any): Int = requireNotNull(
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $predicate", Int::class.java, *values)
    )
}
