package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.controller.Preconditions
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.createPlanLatest
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/**
 * Предусловия команд: что предъявляется, что принимается и чем отвечает отказ.
 *
 * Проверяется целиком опубликованная матрица, а не отдельные удачные случаи. Коды разведены
 * не для красоты: 428 говорит «добавьте заголовок», 400 — «заголовок нечитаем», 409 — «вы
 * опоздали», 404 — «такого ресурса для вас нет». Каждый требует от клиента разного, и
 * склеить их значило бы заставить его гадать.
 *
 * Транзакции на классе нет намеренно: команды должны доходить до базы, иначе версия не
 * продвигается и проверять было бы нечего.
 */
@PostgresIntegrationTest
class PreconditionMatrixTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val mockMvc: MockMvc by lazy {
        MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private val tx: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    private fun asUser(userId: UUID) = jwt().jwt { it.subject(userId.toString()) }

    /** Препарат с владельцем: почти каждой проверке ниже нужен ровно такой набор. */
    private fun scenario(): Triple<UUID, UUID, UUID> = tx.execute {
        val alice = dbHelper.freshUser("precondition")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        Triple(alice.id, kit.id, drug.id)
    }!!

    // ── Тег ──────────────────────────────────────────────────────────────────────

    @Test
    fun `чтение препарата отдаёт сильный тег, равный версии в теле`() {
        val (alice, _, drugId) = scenario()

        mockMvc.perform(get("/v1/drugs/$drugId").with(asUser(alice)))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
            .andExpect(jsonPath("$.version").value(0))
    }

    @Test
    fun `тег из чтения годится как предусловие, и команда отдаёт следующий`() {
        val (alice, _, drugId) = scenario()

        val etag = mockMvc.perform(get("/v1/drugs/$drugId").with(asUser(alice)))
            .andReturn().response.getHeader(HttpHeaders.ETAG)
        assertNotNull(etag)

        mockMvc.perform(
            patch("/v1/drugs/$drugId").with(asUser(alice))
                .header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}""")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
            .andExpect(jsonPath("$.version").value(1))
    }

    // ── Отказы ───────────────────────────────────────────────────────────────────

    @Test
    fun `команда без предусловия отвергается кодом 428`() {
        val (alice, _, drugId) = scenario()

        mockMvc.perform(delete("/v1/drugs/$drugId").with(asUser(alice)))
            .andExpect(status().isPreconditionRequired)
    }

    /**
     * Всё, что не одиночный сильный тег, — 400.
     *
     * `*` отвергается вместе с остальным, хотя RFC 9110 его допускает: он утверждает лишь
     * «ресурс существует» и от потерянного обновления не защищает, а предусловия здесь ровно
     * за этим и заведены.
     */
    @Test
    fun `нечитаемое предусловие отвергается кодом 400`() {
        val (alice, _, drugId) = scenario()

        listOf("W/\"0\"", "*", "\"0\", \"1\"", "0", "\"\"", "\"abc\"")
            .forEach { header ->
                mockMvc.perform(delete("/v1/drugs/$drugId").with(asUser(alice)).header(HttpHeaders.IF_MATCH, header))
                    .andExpect(status().isBadRequest)
            }
    }

    @Test
    fun `устаревшее предусловие отвергается кодом 409`() {
        val (alice, _, drugId) = scenario()
        tx.execute { drugService.consume(drugId, qty(1.0), alice, 0) }

        mockMvc.perform(
            patch("/v1/drugs/$drugId").with(asUser(alice))
                .header(HttpHeaders.IF_MATCH, Preconditions.etag(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}""")
        )
            .andExpect(status().isConflict)
    }

    /**
     * Доступ решается раньше версии.
     *
     * Иначе по коду ответа можно было бы узнать, что чужой препарат существует: достаточно
     * было бы перебирать версии и смотреть, когда 409 сменится на 404.
     */
    @Test
    fun `чужой препарат отвечает 404 при любой предъявленной версии`() {
        val (_, _, drugId) = scenario()
        val eve = tx.execute { dbHelper.freshUser("eve") }!!

        listOf(0L, 1L, 999L).forEach { version ->
            mockMvc.perform(
                delete("/v1/drugs/$drugId").with(asUser(eve.id))
                    .header(HttpHeaders.IF_MATCH, Preconditions.etag(version))
            )
                .andExpect(status().isNotFound)
        }
    }

    // ── Где предусловия нет ──────────────────────────────────────────────────────

    @Test
    fun `создание препарата предусловия не требует и отдаёт тег`() {
        val (alice, kitId, _) = scenario()

        mockMvc.perform(
            post("/v1/med-kits/$kitId/drugs").with(asUser(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"New","quantity":5.0,"quantityUnitId":"${tx.execute { dbHelper.unit() }!!.id}"}"""
                )
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
    }

    // ── Аптечка ──────────────────────────────────────────────────────────────────

    @Test
    fun `выход из аптечки требует её версию, а не версию препарата`() {
        val (alice, kitId, _) = scenario()
        val bob = tx.execute { dbHelper.freshUser("bob") }!!
        tx.execute { medKitService.joinByInvitation(medKitService.invite(kitId, alice), bob.id) }

        mockMvc.perform(delete("/v1/med-kit-memberships/$kitId").with(asUser(bob.id)))
            .andExpect(status().isPreconditionRequired)

        val version = tx.execute { medKitService.requireById(kitId).version }!!
        mockMvc.perform(
            delete("/v1/med-kit-memberships/$kitId").with(asUser(bob.id))
                .header(HttpHeaders.IF_MATCH, Preconditions.etag(version))
        )
            .andExpect(status().isNoContent)
    }

    // ── Приём ────────────────────────────────────────────────────────────────────

    @Test
    fun `повтор приёма с тем же идентификатором не списывает второй раз`() {
        val (alice, _, drugId) = scenario()
        tx.execute { drugService.createPlanLatest(alice, drugId, qty(20.0)) }
        val version = tx.execute { drugService.require(drugId, alice).version }!!
        val intakeId = UUID.randomUUID()
        val body = objectMapper.writeValueAsString(IntakeRequest(drugId, qty(3.0)))

        fun send() = mockMvc.perform(
            put("/v1/intakes/$intakeId").with(asUser(alice))
                .header(HttpHeaders.IF_MATCH, Preconditions.etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )

        val first = send().andExpect(status().isOk).andReturn().response.contentAsString
        val replay = send().andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(first, replay, "повтор обязан вернуть тот же результат, а не выполнить приём заново")
        assertEquals(qty(97.0), tx.execute { drugService.require(drugId, alice).quantity.amount })
    }

    @Test
    fun `тот же идентификатор с другим содержимым отвергается кодом 409`() {
        val (alice, _, drugId) = scenario()
        tx.execute { drugService.createPlanLatest(alice, drugId, qty(20.0)) }
        val version = tx.execute { drugService.require(drugId, alice).version }!!
        val intakeId = UUID.randomUUID()

        fun send(amount: Double) = mockMvc.perform(
            put("/v1/intakes/$intakeId").with(asUser(alice))
                .header(HttpHeaders.IF_MATCH, Preconditions.etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(IntakeRequest(drugId, qty(amount))))
        )

        send(3.0).andExpect(status().isOk)
        send(5.0).andExpect(status().isConflict)

        assertEquals(qty(97.0), tx.execute { drugService.require(drugId, alice).quantity.amount })
    }

    @Test
    fun `план лечения предъявляет версию своего препарата`() {
        val (alice, _, drugId) = scenario()

        mockMvc.perform(
            post("/v1/treatment-plans").with(asUser(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TreatmentPlanCreateRequest(drugId, qty(10.0))))
        )
            .andExpect(status().isPreconditionRequired)

        mockMvc.perform(
            post("/v1/treatment-plans").with(asUser(alice))
                .header(HttpHeaders.IF_MATCH, Preconditions.etag(0))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TreatmentPlanCreateRequest(drugId, qty(10.0))))
        )
            .andExpect(status().isCreated)
            // План — часть препарата, поэтому его появление продвинуло версию корня.
            .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
            .andExpect(jsonPath("$.drugVersion").value(1))
    }
}
