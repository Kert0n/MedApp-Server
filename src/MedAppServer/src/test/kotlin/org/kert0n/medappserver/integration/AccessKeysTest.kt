package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Ключи проверяются отдельно от правил, которые они страхуют.
 *
 * Правила живут в коде и проверены своими тестами: выход снимает брони, переезд снимает брони
 * тех, кто цель не видит. Здесь приложение обходится стороной — правки идут прямым SQL, — и
 * проверяется, что схема не даст оставить бронь без доступа даже тогда, когда уборка не
 * отработала. Иначе страховка была бы декоративной.
 */
@PostgresIntegrationTest
class AccessKeysTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    @Test
    fun `утрата членства уносит бронь мимо приложения`() {
        val alice = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        // Строка членства удаляется в обход сервисов: приложение свои брони убрало бы само.
        jdbc.update("DELETE FROM user_med_kits WHERE med_kit_id = ? AND user_id = ?", kit.id, alice.id)

        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM reservations WHERE drug_id = ?", Int::class.java, drug.id),
            "бронь обязана уйти каскадом с членством"
        )
    }

    @Test
    fun `переезд к постороннему упирается в ключ, а не оставляет чужую бронь`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val shared = dbHelper.freshMedKit(alice.id)
        dbHelper.join(shared.id, alice.id, bob.id)
        val aliceOnly = dbHelper.freshMedKit(alice.id)

        val drug = dbHelper.freshDrug(shared.id, 10.0)
        dbHelper.reserve(bob.id, drug.id, qty(3.0))

        // Переезд без уборки: ровно то, что делает DrugRelocation, но без снятия чужих броней.
        val refused = runCatching {
            jdbc.update("UPDATE user_drugs SET med_kit_id = ? WHERE id = ?", aliceOnly.id, drug.id)
        }.exceptionOrNull()

        assertTrue(
            refused is DataIntegrityViolationException,
            "перенос обязан упереться в ключ членства, а не тихо оставить бронь Боба: $refused"
        )
        assertEquals(
            shared.id,
            jdbc.queryForObject("SELECT med_kit_id FROM user_drugs WHERE id = ?", java.util.UUID::class.java, drug.id),
            "упаковка осталась на месте"
        )
    }

    @Test
    fun `бронь переезжает вместе с пачкой, когда доступ сохраняется`() {
        val alice = dbHelper.freshUser("alice")
        val from = dbHelper.freshMedKit(alice.id)
        val to = dbHelper.freshMedKit(alice.id)
        val drug = dbHelper.freshDrug(from.id, 10.0)
        dbHelper.reserve(alice.id, drug.id, qty(4.0))

        jdbc.update("UPDATE user_drugs SET med_kit_id = ? WHERE id = ?", to.id, drug.id)

        assertEquals(
            to.id,
            jdbc.queryForObject(
                "SELECT med_kit_id FROM reservations WHERE drug_id = ?", java.util.UUID::class.java, drug.id
            ),
            "ON UPDATE CASCADE обязан сдвинуть аптечку брони вслед за пачкой"
        )
        assertNull(
            jdbc.queryForObject("SELECT count(*) FROM reservations WHERE med_kit_id = ?", Int::class.java, from.id)
                .takeIf { it != 0 },
            "в исходной аптечке броней не осталось"
        )
    }
}
