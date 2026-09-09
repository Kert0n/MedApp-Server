package org.kert0n.medappserver.integration

import java.math.BigDecimal
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Нарушение ключа доезжает до доменного отказа, а не до пятисотки.
 *
 * Тест идёт через настоящий PostgreSQL намеренно: разбирается текст ошибки драйвера, и проверять
 * его на выдуманном сообщении значило бы проверять собственную выдумку. Здесь ломается ровно то,
 * что сломается в проде, — формат сообщения тоже под проверкой.
 */
@PostgresIntegrationTest
class ConstraintTranslationTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var medKits: MedKitStore
    @Autowired private lateinit var reservations: ReservationStore
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `повторное вступление переводится в AlreadyMember`() {
        val owner = dbHelper.freshUser("constraint-member-owner")
        val member = dbHelper.freshUser("constraint-member-joiner")
        val kit = dbHelper.freshMedKit(owner.id)
        dbHelper.join(kit.id, owner.id, member.id)

        assertFailsWith<AlreadyMember> {
            inTransaction { medKits.insertMembership(kit.id, member.id) }
        }
    }

    @Test
    fun `повторная бронь переводится в ReservationAlreadyExists`() {
        val owner = dbHelper.freshUser("constraint-reservation-owner")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 20.0)
        val existing = dbHelper.reserve(owner.id, drug.id, BigDecimal("3"))

        assertFailsWith<ReservationAlreadyExists> {
            inTransaction {
                reservations.insert(existing, drug, dbHelper.storedReservationsVersion(drug.id))
            }
        }
    }

    /**
     * Имя ключа берётся целиком, а не ищется подстрокой.
     *
     * `med_kits_pkey` — подстрока `user_med_kits_pkey`, и поиск подстрокой перевёл бы повторное
     * вступление в «аптечка уже существует» в зависимости от порядка объявления в карте отказов.
     * Проверка выше это ловит; здесь то же самое сказано с другой стороны: чужое нарушение не
     * должно выдаваться за известный отказ только потому, что его имя похоже.
     */
    @Test
    fun `чужое нарушение ключа не выдаётся за доменный отказ`() {
        val owner = dbHelper.freshUser("constraint-foreign-owner")
        val outsider = dbHelper.freshUser("constraint-foreign-eve")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 20.0)

        // Бронь постороннего нарушает reservations_membership_fkey. Ни одного известного имени
        // целиком внутри него нет, значит перевода быть не должно — наружу летит ошибка БД.
        val failure = runCatching {
            inTransaction {
                reservations.insert(
                    Reservation(outsider.id, drug.id, Quantity(BigDecimal.ONE, dbHelper.unit())),
                    drug,
                    dbHelper.storedReservationsVersion(drug.id)
                )
            }
        }.exceptionOrNull()

        assertNotNull(failure, "нарушение внешнего ключа обязано было случиться")
        assertFalse(
            generateSequence(failure) { it.cause }.any { it is DomainRuleViolated },
            "чужое нарушение выдано за доменный отказ: $failure"
        )
    }

    private fun inTransaction(work: () -> Unit) {
        TransactionTemplate(transactionManager).execute { work() }
    }
}
