package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Синхронизация одной пачки.
 *
 * Смысл эндпойнта в том, чего в тестах не видно напрямую: между списанием и правкой брони не
 * бывает состояния, в котором пачка уже уменьшилась, а бронь ещё нет. Проверяется это по
 * следствиям — обе половины применяются вместе, отказ любой из них не оставляет ни одной, а
 * повтор не списывает второй раз.
 */
@SpringBootTest
@ActiveProfiles("test")
class DrugSyncTest {

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

    // ── Обе половины вместе ──────────────────────────────────────────────────────

    @Test
    fun `приём и бронь применяются одним запросом`() {
        val world = world()
        dbHelper.reserve(world.userId, world.drugId, qty(50.0))

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0", reservedTo = "45.0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.quantity").value(95.000000))
            .andExpect(jsonPath("$.reservation.amount").value(45.000000))

        assertEquals(qty(95.0), dbHelper.drugQuantity(world.drugId))
        assertEquals(qty(45.0), dbHelper.userReservation(world.userId, world.drugId))
    }

    /** Брони ещё нет — её заводит тот же запрос: клиент не обязан знать, первый он или нет. */
    @Test
    fun `бронь заводится тем же запросом, если её ещё не было`() {
        val world = world()

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0", reservedTo = "20.0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reservation.amount").value(20.000000))

        assertEquals(qty(20.0), dbHelper.userReservation(world.userId, world.drugId))
    }

    @Test
    fun `половины по отдельности тоже допустимы`() {
        val world = world()

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0"))
            .andExpect(status().isOk)
        assertEquals(qty(95.0), dbHelper.drugQuantity(world.drugId))
        assertNull(dbHelper.userReservation(world.userId, world.drugId), "брони не просили")

        mockMvc.perform(world.sync(UUID.randomUUID(), reservedTo = "30.0"))
            .andExpect(status().isOk)
        assertEquals(qty(95.0), dbHelper.drugQuantity(world.drugId), "второй раз не списывали")
        assertEquals(qty(30.0), dbHelper.userReservation(world.userId, world.drugId))
    }

    @Test
    fun `запрос, не просящий ничего, отвергается`() {
        val world = world()

        mockMvc.perform(world.sync(UUID.randomUUID()))
            .andExpect(status().isBadRequest)
    }

    // ── Отказ не оставляет половины ──────────────────────────────────────────────

    /**
     * Списание сверх пачки отменяет и бронь.
     *
     * Иначе получилось бы худшее из обоих миров: приём не прошёл, а заявленное уже переписано по
     * его результату.
     */
    @Test
    fun `отказ приёма не применяет и бронь`() {
        val world = world()
        dbHelper.reserve(world.userId, world.drugId, qty(50.0))

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "500.0", reservedTo = "45.0"))
            .andExpect(status().isBadRequest)

        assertEquals(qty(100.0), dbHelper.drugQuantity(world.drugId), "пачка цела")
        assertEquals(qty(50.0), dbHelper.userReservation(world.userId, world.drugId), "бронь прежняя")
    }

    /** Устаревшая версия брони отменяет и уже посчитанное списание. */
    @Test
    fun `устаревшая версия брони не даёт применить приём`() {
        val world = world()
        dbHelper.reserve(world.userId, world.drugId, qty(50.0))
        val staleReservationVersion = dbHelper.reservationVersion(world.userId, world.drugId) + 1

        mockMvc.perform(
            world.sync(UUID.randomUUID(), consumed = "5.0", reservedTo = "45.0", reservationVersion = staleReservationVersion)
        )
            .andExpect(status().isConflict)

        assertEquals(qty(100.0), dbHelper.drugQuantity(world.drugId), "пачка цела")
        assertEquals(qty(50.0), dbHelper.userReservation(world.userId, world.drugId), "бронь прежняя")
    }

    @Test
    fun `устаревшая версия упаковки отвергается`() {
        val world = world()

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0", drugVersion = 999))
            .andExpect(status().isConflict)

        assertEquals(qty(100.0), dbHelper.drugQuantity(world.drugId))
    }

    // ── Повтор ───────────────────────────────────────────────────────────────────

    /**
     * Тот же запрос дважды — одно списание.
     *
     * Второй вызов приходит с теми же версиями, которые первый уже сдвинул. Требовать их
     * заново значило бы отказывать клиенту за то, что его первый запрос дошёл, поэтому у
     * повтора предусловия не проверяются вовсе.
     */
    @Test
    fun `повтор не списывает второй раз`() {
        val world = world()
        val syncId = UUID.randomUUID()
        val request = world.sync(syncId, consumed = "5.0", reservedTo = "20.0")

        mockMvc.perform(request).andExpect(status().isOk)
        mockMvc.perform(world.sync(syncId, consumed = "5.0", reservedTo = "20.0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.quantity").value(95.000000))

        assertEquals(qty(95.0), dbHelper.drugQuantity(world.drugId), "списание ровно одно")
        assertEquals(qty(20.0), dbHelper.userReservation(world.userId, world.drugId))
    }

    @Test
    fun `тот же идентификатор с другим содержимым отвергается 409`() {
        val world = world()
        val syncId = UUID.randomUUID()

        mockMvc.perform(world.sync(syncId, consumed = "5.0")).andExpect(status().isOk)
        mockMvc.perform(world.sync(syncId, consumed = "7.0")).andExpect(status().isConflict)

        assertEquals(qty(95.0), dbHelper.drugQuantity(world.drugId), "второе списание не прошло")
    }

    /** Разные идентификаторы — разные команды, даже если содержимое совпадает до цифры. */
    @Test
    fun `одинаковые запросы с разными идентификаторами применяются оба`() {
        val world = world()

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0")).andExpect(status().isOk)
        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "5.0")).andExpect(status().isOk)

        assertEquals(qty(90.0), dbHelper.drugQuantity(world.drugId))
    }

    // ── Пачка кончилась ──────────────────────────────────────────────────────────

    /** Опустевшая пачка уничтожается, и бронь на ней тоже: держать заявку на выброшенное нечем. */
    @Test
    fun `приём, опустошивший пачку, уносит и бронь`() {
        val world = world()
        dbHelper.reserve(world.userId, world.drugId, qty(50.0))

        mockMvc.perform(world.sync(UUID.randomUUID(), consumed = "100.0", reservedTo = "10.0"))
            .andExpect(status().isNoContent)

        assertNull(dbHelper.drug(world.drugId), "пачки нет")
        assertNull(dbHelper.userReservation(world.userId, world.drugId), "брони тоже")
    }

    /**
     * Повтор запроса, уничтожившего пачку, отвечает 404.
     *
     * До журнала дело не доходит: доступ проверяется первым, а доступа к тому, чего нет, не
     * бывает. Клиенту это говорит правду — пачки больше не существует, — и второго списания
     * всё равно не случается, потому что списывать не из чего.
     */
    @Test
    fun `повтор запроса, уничтожившего пачку, отвечает что пачки нет`() {
        val world = world()
        val syncId = UUID.randomUUID()

        mockMvc.perform(world.sync(syncId, consumed = "100.0")).andExpect(status().isNoContent)
        mockMvc.perform(world.sync(syncId, consumed = "100.0", drugVersion = 0))
            .andExpect(status().isNotFound)
    }

    // ── Доступ ───────────────────────────────────────────────────────────────────

    @Test
    fun `чужая пачка не синхронизируется`() {
        val world = world()
        val stranger = dbHelper.freshUser("eve")

        mockMvc.perform(
            put("${ApiRoutes.drug(world.drugId)}/sync/${UUID.randomUUID()}")
                .with(jwt().jwt { it.subject(stranger.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"consumed":5.0,"drugVersion":0}""")
        )
            .andExpect(status().isNotFound)

        assertEquals(qty(100.0), dbHelper.drugQuantity(world.drugId))
    }

    // ── Оснастка ─────────────────────────────────────────────────────────────────

    private fun world(): World {
        val owner = dbHelper.freshUser("alice")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        return World(owner.id, drug.id)
    }

    private inner class World(val userId: UUID, val drugId: UUID) {

        fun sync(
            syncId: UUID,
            consumed: String? = null,
            reservedTo: String? = null,
            drugVersion: Long? = null,
            reservationVersion: Long? = null
        ) = put("${ApiRoutes.drug(drugId)}/sync/$syncId")
            .with(jwt().jwt { it.subject(userId.toString()) })
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(consumed, reservedTo, drugVersion, reservationVersion))

        private fun body(
            consumed: String?,
            reservedTo: String?,
            drugVersion: Long?,
            reservationVersion: Long?
        ): String {
            val fields = mutableListOf<String>()
            consumed?.let { fields += """"consumed":$it""" }
            fields += """"drugVersion":${drugVersion ?: dbHelper.drugVersion(drugId)}"""
            reservedTo?.let {
                val version = reservationVersion
                    ?: dbHelper.reservationVersionOrNull(userId, drugId)
                val versionField = version?.let { v -> ""","version":$v""" } ?: ""
                fields += """"reservation":{"amount":$it$versionField}"""
            }
            return fields.joinToString(",", "{", "}")
        }
    }
}
