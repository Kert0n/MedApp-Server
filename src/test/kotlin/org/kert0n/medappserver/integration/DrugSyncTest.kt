package org.kert0n.medappserver.integration

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Синхронизация офлайн-изменений одной упаковки.
 *
 * Проверяется то, ради чего она и заведена: съеденное и бронь приезжают вместе, повтор ничего
 * не делает, а тот же идентификатор с другим содержимым — конфликт, а не второй повтор. Версии
 * едут телом, но отвечают теми же 428 и 412, что и у остальных команд: 409 клиент читает
 * однозначно — как дефект, а не как повод перечитать.
 */
@PostgresIntegrationTest
class DrugSyncTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `съеденное и бронь применяются вместе`() {
        val owner = dbHelper.freshUser("sync-both")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)

        sync(owner.id, drug.id, Uuid.random(), """{"consumed":"5.0","drugVersion":${dbHelper.drugVersion(drug.id)},"reservation":{"amount":"7.0"}}""")
            .andExpect(status().isOk)

        assertEquals(0, BigDecimal("15.000000").compareTo(dbHelper.drugQuantity(drug.id)!!), "списано ровно съеденное")
        assertEquals(0, BigDecimal("7.000000").compareTo(dbHelper.userReservation(owner.id, drug.id)!!), "бронь задана целиком")
    }

    @Test
    fun `повтор того же запроса не списывает второй раз`() {
        val owner = dbHelper.freshUser("sync-repeat")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val syncId = Uuid.random()
        val body = """{"consumed":"5.0","drugVersion":${dbHelper.drugVersion(drug.id)}}"""

        sync(owner.id, drug.id, syncId, body).andExpect(status().isOk)
        sync(owner.id, drug.id, syncId, body).andExpect(status().isOk)

        assertEquals(
            0,
            BigDecimal("15.000000").compareTo(dbHelper.drugQuantity(drug.id)!!),
            "повтор обязан ничего не делать: клиент шлёт его, не зная, дошёл ли первый"
        )
    }

    @Test
    fun `тот же идентификатор с другим содержимым — конфликт`() {
        val owner = dbHelper.freshUser("sync-conflict")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val syncId = Uuid.random()

        sync(owner.id, drug.id, syncId, """{"consumed":"5.0","drugVersion":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isOk)
        sync(owner.id, drug.id, syncId, """{"consumed":"9.0","drugVersion":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isConflict)
    }

    /** Версия едет телом, но устаревшая означает то же, что в любой команде. */
    @Test
    fun `устаревшая версия упаковки — 412`() {
        val owner = dbHelper.freshUser("sync-stale")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)

        sync(owner.id, drug.id, Uuid.random(), """{"consumed":"1.0","drugVersion":99}""")
            .andExpect(status().isPreconditionFailed)

        assertQty(20.0, dbHelper.drugQuantity(drug.id), "устаревшая версия ничего не списала")
    }

    @Test
    fun `списание без версии упаковки — 428`() {
        val owner = dbHelper.freshUser("sync-unstated")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)

        sync(owner.id, drug.id, Uuid.random(), """{"consumed":"1.0","reservation":{"amount":"2.0"}}""")
            .andExpect(status().isPreconditionRequired)

        assertEquals(null, dbHelper.userReservation(owner.id, drug.id), "часть про бронь тоже не применилась")
    }

    @Test
    fun `устаревшая версия картины броней — 412`() {
        val owner = dbHelper.freshUser("sync-stale-claims")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        dbHelper.reserve(owner.id, drug.id, qty(4.0))
        val stale = dbHelper.reservationsVersion(drug.id, owner.id) + 1

        sync(owner.id, drug.id, Uuid.random(), """{"reservation":{"amount":"2.0","version":$stale}}""")
            .andExpect(status().isPreconditionFailed)

        assertQty(4.0, dbHelper.userReservation(owner.id, drug.id), "бронь не изменилась")
    }

    /** Списание опустошило пачку: её больше нет, и часть про бронь ничего не делает. */
    @Test
    fun `опустошение пачки не делает часть про бронь ошибкой`() {
        val owner = dbHelper.freshUser("sync-empty")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 5.0)

        sync(owner.id, drug.id, Uuid.random(), """{"consumed":"5.0","drugVersion":${dbHelper.drugVersion(drug.id)},"reservation":{"amount":"2.0"}}""")
            .andExpect(status().isOk)

        assertEquals(null, dbHelper.drug(drug.id), "пустая пачка выбрасывается")
    }

    private fun sync(userId: Uuid, drugId: Uuid, syncId: Uuid, body: String) =
        mockMvc.perform(
            put(ApiRoutes.sync(drugId, syncId))
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
}
