package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.kert0n.medappserver.services.orchestrators.QuantityReductionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * То, ради чего количества переведены с `Double` на `numeric(19,6)`.
 *
 * На `Double` дробные дозы не сходились: после трёх списаний по 1/3 остаток равнялся не нулю,
 * а величине порядка 1e-16. Из этого следовали два наблюдаемых дефекта:
 *  - кончившийся препарат не удалялся, потому что проверка была `quantity == 0.0`;
 *  - план не удалялся по той же причине и оставался «висеть» с ничтожным остатком.
 *
 * Тест обязан идти на настоящем Postgres: округление `NUMERIC` в H2 отличается, и на H2 он
 * проверял бы не то поведение, что в проде.
 */
@PostgresIntegrationTest
@Transactional
class FractionalQuantityTest {

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var quantityReductionService: QuantityReductionService
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var usingRepository: UsingRepository
    @Autowired private lateinit var usingService: UsingService

    /** Именованные поля вместо `Pair`: у него `first` и `second` ничего не сообщают. */
    private class Fixture(val user: User, val drug: Drug)

    private fun setUp(quantity: BigDecimal): Fixture {
        val user = userRepository.save(User(hashedKey = "{noop}k"))
        val medKit = medKitRepository.save(MedKit())
        medKit.users.add(user)
        user.medKits.add(medKit)
        medKitRepository.save(medKit)
        val drug = drugRepository.save(
            Drug(
                name = "Дробный", quantity = quantity, quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        return Fixture(user = user, drug = drug)
    }

    @Test
    fun `потребление всего остатка дробными долями удаляет препарат и план`() {
        // 1 таблетка, план на неё же, приём по 1/3 — на Double остаток не сходился к нулю.
        val fixture = setUp(qty(1.0))
        val user = fixture.user
        val drug = fixture.drug
        usingService.createTreatmentPlan(user.id, UsingCreateDTO(drug.id, qty(1.0)))

        val third = BigDecimal.ONE.divide(BigDecimal(3), 6, java.math.RoundingMode.HALF_UP)
        quantityReductionService.applyIntake(user.id, drug.id, third)
        quantityReductionService.applyIntake(user.id, drug.id, third)

        // Остаток после двух приёмов: 1 - 2 * 0.333333 = 0.333334.
        assertQty(0.333334, drugRepository.findById(drug.id).orElse(null)?.quantity)

        // Третий приём забирает ровно остаток — препарат кончился.
        val last = drugRepository.findById(drug.id).orElseThrow().quantity
        val afterLast = quantityReductionService.applyIntake(user.id, drug.id, last)

        assertNull(afterLast, "план должен исчезнуть вместе с кончившимся препаратом")
        assertTrue(
            drugRepository.findById(drug.id).isEmpty,
            "препарат с нулевым остатком должен быть удалён; на Double остаток был ~1e-16 и удаление не срабатывало"
        )
        assertTrue(
            usingRepository.findAllByUsingKeyDrugId(drug.id).isEmpty(),
            "планов не должно остаться"
        )
    }

    @Test
    fun `инвариант держится после пропорционального уменьшения планов`() {
        // Двое зарезервировали всё; владелец списывает часть мимо планов, и планы должны
        // сжаться так, чтобы их сумма ровно равнялась остатку. Именно здесь на Double
        // накапливалась разница: каждое умножение на коэффициент давало свой хвост.
        val fixture = setUp(qty(10.0))
        val alice = fixture.user
        val drug = fixture.drug
        val bob = userRepository.save(User(hashedKey = "{noop}k2"))
        drug.medKit.users.add(bob)
        bob.medKits.add(drug.medKit)
        medKitRepository.save(drug.medKit)

        usingService.createTreatmentPlan(alice.id, UsingCreateDTO(drug.id, qty(7.0)))
        usingService.createTreatmentPlan(bob.id, UsingCreateDTO(drug.id, qty(3.0)))

        // Списываем 1/3 остатка — коэффициент сжатия становится бесконечной дробью.
        val consumed = qty(10.0).divide(BigDecimal(3), 6, java.math.RoundingMode.HALF_UP)
        quantityReductionService.applyIntake(alice.id, drug.id, consumed)

        val remaining = drugRepository.findById(drug.id).orElseThrow().quantity
        val plansTotal = usingRepository.findAllByUsingKeyDrugId(drug.id)
            .fold(BigDecimal.ZERO) { sum, using -> sum + using.plannedAmount }

        // Инвариант — «не больше», а не «ровно». Точное равенство раньше держалось
        // компенсацией: планы округлялись HALF_UP, сумма могла превысить остаток, и разница
        // отдавалась самому большому плану. Округление вниз даёт инвариант по построению, а
        // сумма оказывается меньше остатка на несколько миллионных — при сжатии резерва это
        // единственное уместное направление.
        assertTrue(
            plansTotal <= remaining,
            "сумма планов $plansTotal обязана не превышать остаток $remaining"
        )
        // И при этом почти равна: потеря не должна выходить за один младший разряд на план.
        assertTrue(
            remaining - plansTotal < qty(0.00001),
            "сжатие потеряло слишком много: остаток $remaining, планы $plansTotal"
        )
    }
}
