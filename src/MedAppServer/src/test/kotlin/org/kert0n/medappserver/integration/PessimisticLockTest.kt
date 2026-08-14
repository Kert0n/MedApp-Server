package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверяет, что `@Lock(PESSIMISTIC_WRITE)` действительно держит строку препарата.
 *
 * На общей аптечке двое могут одновременно списывать из одного запаса, и вся защита от гонки
 * держится на этой блокировке. Читать сгенерированный SQL для проверки недостаточно и легко
 * ошибиться: Postgres-диалект Hibernate пишет не `for update`, а `for no key update of ...`,
 * так что поиск подстроки даёт ложный вывод об отсутствии блокировки. Поэтому проверяем
 * поведением — второе соединение пытается взять ту же строку и должно получить отказ.
 *
 * Класс не транзакционный намеренно: фикстура должна быть закоммичена, иначе второе соединение
 * просто не увидит строку, `FOR UPDATE NOWAIT` вернёт пустой результат без ошибки, и тест
 * покажет «не заблокировано» на работающей блокировке.
 */
@PostgresIntegrationTest
class PessimisticLockTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    /** Именованные поля вместо `Pair`: два UUID под именами `first` и `second` не различить. */
    private class Fixture(val drugId: UUID, val userId: UUID)

    private fun createCommittedDrug(): Fixture {
        val user = userRepository.save(User(hashedKey = "{noop}lock-${UUID.randomUUID()}"))
        val medKit = medKitRepository.save(MedKit())
        medKit.users.add(user)
        user.medKits.add(medKit)
        medKitRepository.save(medKit)
        val drug = drugRepository.save(
            Drug(
                name = "Заблокированный", quantity = qty(10.0), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        return Fixture(drugId = drug.id, userId = user.id)
    }

    /**
     * Пытается взять строку со стороны. `NOWAIT` вместо ожидания: иначе тест повис бы до
     * таймаута вместо внятного отказа.
     *
     * Отдельно проверяется, что строка вообще найдена: без этого пустой результат из-за
     * незакоммиченной фикстуры выглядел бы как «блокировки нет».
     */
    private fun otherConnectionCanLock(table: String, id: UUID): Boolean =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT id FROM $table WHERE id = ? FOR UPDATE NOWAIT")
                    .use { statement ->
                        statement.setObject(1, id)
                        statement.executeQuery().use { rs ->
                            assertTrue(rs.next(), "строка $table не найдена — фикстура не закоммичена")
                        }
                    }
                true
            } catch (_: SQLException) {
                false
            } finally {
                connection.rollback()
            }
        }

    @Test
    fun `строка препарата заблокирована, пока держится транзакция`() {
        val fixture = createCommittedDrug()
        val drugId = fixture.drugId
        val userId = fixture.userId

        TransactionTemplate(transactionManager).execute {
            val locked = drugRepository.findByIdAndMedKitUsersIdForUpdate(drugId, userId)
            assertNotNull(locked, "препарат должен найтись")

            assertFalse(
                otherConnectionCanLock("user_drugs", drugId),
                "строка препарата должна быть заблокирована: на этом держится защита от " +
                    "одновременного списания из общей аптечки"
            )
        }

        // После завершения транзакции блокировка снимается.
        assertTrue(
            otherConnectionCanLock("user_drugs", drugId),
            "после коммита блокировка должна отпускаться"
        )
    }

    @Test
    fun `блокировка не захватывает строки пользователя и аптечки`() {
        // Запрос джойнит med_kits и user_med_kits. Если бы FOR UPDATE распространялся на все
        // таблицы джойна, участники одной аптечки блокировали бы друг друга на несвязанных
        // операциях. Hibernate ограничивает область через `of d1_0` — это здесь и проверяется.
        val fixture = createCommittedDrug()
        val drugId = fixture.drugId
        val userId = fixture.userId

        TransactionTemplate(transactionManager).execute {
            drugRepository.findByIdAndMedKitUsersIdForUpdate(drugId, userId)

            assertTrue(
                otherConnectionCanLock("users", userId),
                "строка пользователя не должна блокироваться"
            )
        }
    }
}
