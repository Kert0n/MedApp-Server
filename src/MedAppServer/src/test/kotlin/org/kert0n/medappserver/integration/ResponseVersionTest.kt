package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Версия в ответе команды — та, что в базе.
 *
 * Клиент делает следующий шаг по тому, что ему вернули, а не по отдельному чтению: так написано
 * в контракте. Значит, версия в теле и `ETag` обязаны совпадать с записанной строкой — иначе
 * первая же следующая команда получает отказ по тегу, который сервер сам только что и выдал.
 *
 * Ловушка ровно одна: команда возвращает доменный объект, посчитанный **до** записи, а версию
 * двигает Hibernate на flush. Копия про это не знает.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResponseVersionTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `PATCH брони отдаёт версию, записанную в базу`() {
        val world = world()
        reservationService.create(world.userId, world.drugId, qty(20.0))

        val response = mockMvc.perform(patchReservation(world, "30.0", dbHelper.reservationVersion(world.userId, world.drugId)))
            .andExpect(status().isOk)
            .andReturn().response

        val stored = dbHelper.reservationVersion(world.userId, world.drugId)
        assertEquals(tag(stored), response.getHeader(HttpHeaders.ETAG), "тег обязан совпасть с базой")
        assertEquals(stored, versionIn(response.contentAsString), "версия в теле обязана совпасть с базой")
    }

    /** Главное следствие: тегом из ответа можно сразу пользоваться. */
    @Test
    fun `тегом из ответа PATCH сразу делается следующая команда`() {
        val world = world()
        reservationService.create(world.userId, world.drugId, qty(20.0))

        val first = mockMvc.perform(patchReservation(world, "30.0", dbHelper.reservationVersion(world.userId, world.drugId)))
            .andExpect(status().isOk)
            .andReturn().response
        val fromResponse = assertNotNull(first.getHeader(HttpHeaders.ETAG))

        mockMvc.perform(
            patch(ApiRoutes.reservation(world.drugId))
                .with(world.caller())
                .header(HttpHeaders.IF_MATCH, fromResponse)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":40.0}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `создание брони отдаёт версию, записанную в базу`() {
        val world = world()

        val response = mockMvc.perform(
            post(ApiRoutes.RESERVATIONS)
                .with(world.caller())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"drugId":"${world.drugId}","amount":20.0}""")
        )
            .andExpect(status().isCreated)
            .andReturn().response

        val stored = dbHelper.reservationVersion(world.userId, world.drugId)
        assertEquals(tag(stored), response.getHeader(HttpHeaders.ETAG))
        assertEquals(stored, versionIn(response.contentAsString))
    }

    /** У упаковки та же обязанность, хотя путь другой: контроллер перечитывает после команды. */
    @Test
    fun `PATCH упаковки отдаёт версию, записанную в базу`() {
        val world = world()

        val response = mockMvc.perform(
            patch(ApiRoutes.drug(world.drugId))
                .with(world.caller())
                .header(HttpHeaders.IF_MATCH, tag(dbHelper.drugVersion(world.drugId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"vitamins"}""")
        )
            .andExpect(status().isOk)
            .andReturn().response

        assertEquals(tag(dbHelper.drugVersion(world.drugId)), response.getHeader(HttpHeaders.ETAG))
    }

    /**
     * Синхронизация возвращает оба ресурса и обе версии, читая их внутри своей транзакции.
     *
     * Тега у ответа нет — ресурсов два, — поэтому проверяются оба тела.
     */
    @Test
    fun `синхронизация отдаёт версии, записанные в базу`() {
        val world = world()
        reservationService.create(world.userId, world.drugId, qty(50.0))

        val body = mockMvc.perform(
            put("${ApiRoutes.drug(world.drugId)}/sync/${UUID.randomUUID()}")
                .with(world.caller())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"consumed":5.0,"drugVersion":${dbHelper.drugVersion(world.drugId)},""" +
                        """"reservation":{"amount":45.0,"version":${dbHelper.reservationVersion(world.userId, world.drugId)}}}"""
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.version").value(dbHelper.drugVersion(world.drugId)))
            .andExpect(jsonPath("$.reservation.version").value(dbHelper.reservationVersion(world.userId, world.drugId)))
            .andReturn().response.contentAsString

        // И тем, что вернулось, можно сразу продолжать: следующий приём с этим тегом проходит.
        mockMvc.perform(
            post(ApiRoutes.intakes(world.drugId))
                .with(world.caller())
                .header(HttpHeaders.IF_MATCH, tag(drugVersionIn(body)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":1.0}""")
        )
            .andExpect(status().isOk)
    }

    // ── Оснастка ─────────────────────────────────────────────────────────────────

    private fun patchReservation(world: World, amount: String, version: Long) =
        patch(ApiRoutes.reservation(world.drugId))
            .with(world.caller())
            .header(HttpHeaders.IF_MATCH, tag(version))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":$amount}""")

    private fun world(): World {
        val owner = dbHelper.freshUser("alice")
        val kit = medKitService.create(owner.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        return World(owner.id, drug.id)
    }

    private inner class World(val userId: UUID, val drugId: UUID) {
        fun caller() = jwt().jwt { it.subject(userId.toString()) }
    }

    private fun tag(version: Long) = "\"$version\""

    private fun versionIn(json: String): Long = Regex("\"version\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private fun drugVersionIn(json: String): Long =
        Regex("\"drug\":\\{.*?\"version\":(\\d+)").find(json)!!.groupValues[1].toLong()
}
