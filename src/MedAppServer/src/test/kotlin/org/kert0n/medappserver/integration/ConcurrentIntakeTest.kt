package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Конкурирующие команды выполняются в отдельных транзакциях и не теряют изменения. */
@PostgresIntegrationTest
class ConcurrentIntakeTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired private lateinit var drugCommands: DrugCommandService
    @Autowired private lateinit var medKitLifecycle: MedKitLifecycleService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitFixture: MedKitFixture
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `одновременные приёмы двух участников списывают оба`() {
        val alice = userRepository.save(User(hashedKey = "{noop}conc-${UUID.randomUUID()}"))
        val medKit = medKitRepository.save(MedKit())
        medKit.users.add(alice)
        alice.medKits.add(medKit)
        medKitRepository.save(medKit)

        val bob = userRepository.save(User(hashedKey = "{noop}conc-${UUID.randomUUID()}"))
        medKitFixture.addUserToMedKit(medKit.id, bob.id)

        val drug = drugRepository.save(
            Drug(
                name = "Общий", quantity = qty(100.0), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        treatmentPlanService.create(alice.id, drug.id, qty(40.0))
        treatmentPlanService.create(bob.id, drug.id, qty(40.0))

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val errors = mutableListOf<Throwable>()

        val tasks = listOf(alice.id, bob.id).map { userId ->
            pool.submit {
                start.await()
                runCatching { treatmentPlanService.applyIntake(userId, drug.id, qty(10.0)) }
                    .onFailure { synchronized(errors) { errors += it } }
            }
        }
        start.countDown()
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(errors.isEmpty(), "приёмы не должны падать: ${errors.map { it.message }}")

        assertQty(
            80.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "оба списания обязаны попасть в остаток"
        )
    }

    @Test
    fun `consume intake и patch не теряют изменения`() {
        val alice = userRepository.save(User(hashedKey = "{noop}conc-${UUID.randomUUID()}"))
        val medKit = medKitFixture.createNew(alice.id)
        val bob = userRepository.save(User(hashedKey = "{noop}conc-${UUID.randomUUID()}"))
        medKitFixture.addUserToMedKit(medKit.id, bob.id)
        val drug = drugRepository.save(
            Drug(
                name = "Concurrent commands", quantity = qty(100.0), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        treatmentPlanService.create(alice.id, drug.id, qty(40.0))
        treatmentPlanService.create(bob.id, drug.id, qty(40.0))

        runConcurrently(
            { drugCommands.consume(alice.id, drug.id, qty(10.0)) },
            { treatmentPlanService.applyIntake(alice.id, drug.id, qty(10.0)) },
            { treatmentPlanService.patch(bob.id, drug.id, qty(30.0)) }
        )

        assertQty(80.0, drugRepository.findById(drug.id).orElseThrow().quantity)
        assertQty(30.0, usingService.getForUser(alice.id, drug.id).plannedAmount)
        assertQty(30.0, usingService.getForUser(bob.id, drug.id).plannedAmount)
    }

    @Test
    fun `move и delete аптечки завершаются без deadlock`() {
        val owner = userRepository.save(User(hashedKey = "{noop}conc-${UUID.randomUUID()}"))
        val source = medKitFixture.createNew(owner.id)
        val target = medKitFixture.createNew(owner.id)
        val drug = drugRepository.save(
            Drug(
                name = "Move or delete", quantity = qty(10.0), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = source
            )
        )
        val errors = runConcurrently(
            { drugCommands.move(owner.id, drug.id, target.id) },
            { medKitLifecycle.delete(owner.id, source.id) }
        )

        assertTrue(
            errors.all { it is ResponseStatusException },
            "допустим только проигравший гонку с 404, но не deadlock: ${errors.map { it.message }}"
        )
        assertTrue(!medKitRepository.existsById(source.id))
        val targetId = jdbc.query(
            "SELECT med_kit_id FROM user_drugs WHERE id = ?",
            { row, _ -> UUID.fromString(row.getString(1)) },
            drug.id
        ).singleOrNull()
        assertTrue(targetId == null || targetId == target.id)
    }

    private fun runConcurrently(vararg commands: () -> Unit): List<Throwable> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(commands.size)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        return try {
            val tasks = commands.map { command ->
                pool.submit {
                    start.await()
                    runCatching(command).onFailure(errors::add)
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            errors.toList()
        } finally {
            pool.shutdownNow()
        }
    }
}
