package org.kert0n.medappserver.integration

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Проверяет допустимый рост DML при согласовании планов и отсутствие роста при cascade.
 */
@PostgresIntegrationTest
class StatementCountTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var drugCommands: DrugCommandService
    @Autowired private lateinit var medKitFixture: MedKitFixture
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    private val statistics by lazy {
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics
            .also { it.isStatisticsEnabled = true }
    }

    private class Fixture(val owner: User, val drug: Drug)

    /**
     * Препарат с [plans] планами от разных пользователей одной аптечки.
     *
     * Запас берётся вдвое больше суммы планов, чтобы создание планов не упиралось в проверку
     * доступного количества.
     */
    private fun fixture(plans: Int): Fixture {
        val medKit = medKitRepository.save(MedKit())
        val owner = userRepository.save(User(hashedKey = "{noop}stat-${UUID.randomUUID()}"))
        medKit.users.add(owner)
        owner.medKits.add(medKit)
        medKitRepository.save(medKit)

        val drug = drugRepository.save(
            Drug(
                name = "Счётный", quantity = qty(plans * 20.0), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )

        repeat(plans) { index ->
            val user = if (index == 0) owner else {
                userRepository.save(User(hashedKey = "{noop}stat-${UUID.randomUUID()}"))
                    .also { medKitFixture.addUserToMedKit(medKit.id, it.id) }
            }
            treatmentPlanService.create(user.id, drug.id, qty(10.0))
        }
        return Fixture(owner, drug)
    }

    private fun statementsFor(action: () -> Unit): Long {
        statistics.clear()
        action()
        return statistics.prepareStatementCount
    }

    /** Урезание остатка ниже суммы планов: все планы сжимаются пропорционально. */
    private fun shrinkStatements(plans: Int): Long {
        val fixture = fixture(plans)
        return statementsFor {
            drugCommands.consume(
                fixture.owner.id,
                fixture.drug.id,
                qty(plans * 15.0)
            )
        }
    }

    /** Полное потребление остатка владельцем: препарат удаляется вместе со всеми планами. */
    private fun deleteStatements(plans: Int): Long {
        val fixture = fixture(plans)
        return statementsFor {
            drugCommands.consume(fixture.owner.id, fixture.drug.id, fixture.drug.quantity)
        }
    }

    @Test
    fun `сжатие планов добавляет один оператор на план, а не два`() {
        val two = shrinkStatements(2)
        val five = shrinkStatements(5)

        println("сжатие: 2 плана — $two операторов, 5 планов — $five")
        assertEquals(
            3L, five - two,
            "три лишних плана должны добавить три UPDATE и больше ничего. Прирост шесть — " +
                "значит на каждый план завёлся отдельный SELECT"
        )
    }

    @Test
    fun `удаление препарата не добавляет операторов на планы`() {
        val two = deleteStatements(2)
        val five = deleteStatements(5)

        println("удаление: 2 плана — $two операторов, 5 планов — $five")
        assertEquals(
            0L, five - two,
            "дочерние планы должна удалить база данных тем же каскадом"
        )
    }
}
