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
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Два одновременных приёма из общей аптечки не теряют списание.
 *
 * Ради этого в проекте и стоит пессимистическая блокировка. Но из трёх мутирующих путей
 * приём — самый горячий — был единственным, который её не брал: `consume` и
 * `updateTreatmentPlan` лочили препарат, а `applyIntake` читал остаток и уменьшал его на
 * живую. Двое участников общей аптечки, принимающие лекарство в одну секунду, читали одно и
 * то же значение и записывали каждый своё — одно списание пропадало.
 *
 * Класс не транзакционный намеренно: каждый вызов обязан идти своей транзакцией, иначе
 * блокировка не с чем конкурировать и тест проверял бы пустоту.
 */
@PostgresIntegrationTest
class ConcurrentIntakeTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitFixture: MedKitFixture
    @Autowired private lateinit var drugService: DrugService

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

        // Оба потока стартуют по одному сигналу: без этого второй успевал бы отработать
        // после первого, и гонки, которую мы ловим, просто не возникало бы.
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

        // 100 − 10 − 10. Без блокировки один из потоков читал ещё не уменьшенные 100 и
        // записывал 90, затирая чужое списание, — и здесь оказывалось 90.
        assertQty(
            80.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "оба списания обязаны попасть в остаток"
        )
    }
}
