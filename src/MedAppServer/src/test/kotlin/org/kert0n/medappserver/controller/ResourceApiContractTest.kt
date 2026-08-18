package org.kert0n.medappserver.controller

import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.CatalogueService
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.qty
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/**
 * Опубликованная поверхность API.
 *
 * Проверяется адресация, а не бизнес-правила: что операция живёт по заявленному пути, что
 * тело и код ответа те, что обещаны, и что старых путей больше нет. Поведение самих
 * операций закреплено сервисными и интеграционными тестами.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResourceApiContractTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockitoBean private lateinit var drugService: DrugService
    @MockitoBean private lateinit var reservationService: ReservationService
    @MockitoBean private lateinit var medKitService: MedKitService
    @MockitoBean private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator
    @MockitoBean private lateinit var catalogueService: CatalogueService

    private lateinit var mockMvc: MockMvc

    private val userId: UUID = UUID.randomUUID()
    private val medKitId: UUID = UUID.randomUUID()
    private val drugId: UUID = UUID.randomUUID()

    private val medKit = MedKit(medKitId, setOf(userId))
    private val unit = QuantityUnit(UUID.randomUUID(), "mg")
    private val drug = Drug(
        id = drugId, medKitId = medKitId, name = "Aspirin",
        quantity = Quantity(qty(100.0), unit)
    )
    private val drugDto = drug.toDto(emptyList())

    /** Тег того состояния, по которому «решал клиент»: у свежей заготовки версия нулевая. */
    private val currentTag = "\"0\""

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun asUser() = jwt().jwt { it.subject(userId.toString()) }

    // ── Препараты ────────────────────────────────────────────────────────────────

    @Test
    fun `препарат читается по своему пути`() {
        whenever(medKitDrugOrchestrator.drug(drugId, userId)).thenReturn(drugDto)

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(drugId.toString()))
            .andExpect(jsonPath("$.medKitId").value(medKitId.toString()))
            // Заявленное бронями — справка, и она может превышать остаток.
            .andExpect(jsonPath("$.reservedQuantity").exists())
            .andExpect(jsonPath("$.availableQuantity").doesNotExist())
    }

    @Test
    fun `препарат создаётся в аптечке из пути`() {
        whenever(medKitDrugOrchestrator.createDrugInMedKit(eq(medKitId), any(), eq(userId))).thenReturn(drug)
        whenever(medKitDrugOrchestrator.drug(drugId, userId)).thenReturn(drugDto)
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnitId = unit.id)

        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(drugId.toString()))
    }

    @Test
    fun `нулевое количество при создании отвергается`() {
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(0.0), quantityUnitId = unit.id)

        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `приём создаётся подчинённым ресурсом упаковки`() {
        whenever(drugService.consume(eq(drugId), any(), eq(userId), eq(0L))).thenReturn(drug)
        whenever(medKitDrugOrchestrator.drug(drugId, userId)).thenReturn(drugDto)

        mockMvc.perform(
            post(ApiRoutes.intakes(drugId)).with(asUser())
                .header(HttpHeaders.IF_MATCH, currentTag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":2.0}""")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, currentTag))
            .andExpect(jsonPath("$.id").value(drugId.toString()))
    }

    @Test
    fun `перенос выражен размещением препарата в целевой аптечке`() {
        val target = UUID.randomUUID()
        whenever(medKitDrugOrchestrator.moveDrug(drugId, target, userId, 0L)).thenReturn(drug)
        whenever(medKitDrugOrchestrator.drug(drugId, userId)).thenReturn(drugDto)

        mockMvc.perform(put(ApiRoutes.drugIn(target, drugId)).with(asUser()).header(HttpHeaders.IF_MATCH, currentTag))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(drugId.toString()))
    }

    @Test
    fun `препарат удаляется`() {
        doNothing().whenever(drugService).delete(drugId, userId, 0L)

        mockMvc.perform(delete(ApiRoutes.drug(drugId)).with(asUser()).header(HttpHeaders.IF_MATCH, currentTag))
            .andExpect(status().isNoContent)
    }

    // ── Каталог ──────────────────────────────────────────────────────────────────

    @Test
    fun `каталог ищется параметром query`() {
        whenever(catalogueService.fuzzySearch(eq("аспир"), any())).thenReturn(emptyList())

        mockMvc.perform(get(ApiRoutes.DRUG_TEMPLATES).param("query", "аспир").with(asUser()))
            .andExpect(status().isOk)
    }

    @Test
    fun `предел выдачи каталога ограничен сверху`() {
        mockMvc.perform(
            get(ApiRoutes.DRUG_TEMPLATES).param("query", "аспир").param("limit", "500").with(asUser())
        )
            .andExpect(status().isBadRequest)
    }

    // ── Брони ────────────────────────────────────────────────────────────────────

    @Test
    fun `план лечения создаётся и меняется`() {
        val plan = Reservation(userId = userId, drugId = drugId, amount = Quantity(qty(20.0), unit))
        whenever(medKitDrugOrchestrator.createReservation(eq(userId), eq(drugId), any())).thenReturn(plan)
        whenever(reservationService.changeTo(eq(userId), eq(drugId), any(), eq(0L))).thenReturn(plan)
        whenever(reservationService.require(userId, drugId)).thenReturn(plan)

        mockMvc.perform(
            post(ApiRoutes.RESERVATIONS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ReservationCreateRequest(drugId, qty(20.0))))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            // Отметок времени в контракте нет.
            .andExpect(jsonPath("$.createdAt").doesNotExist())
            .andExpect(jsonPath("$.lastModified").doesNotExist())

        mockMvc.perform(
            patch(ApiRoutes.reservation(drugId)).with(asUser())
                .header(HttpHeaders.IF_MATCH, currentTag)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ReservationPatchRequest(qty(15.0))))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `план лечения удаляется`() {
        doNothing().whenever(reservationService).cancel(userId, drugId, 0L)

        mockMvc.perform(
            delete(ApiRoutes.reservation(drugId)).with(asUser()).header(HttpHeaders.IF_MATCH, currentTag)
        )
            .andExpect(status().isNoContent)
    }

    // ── Аптечки и членство ───────────────────────────────────────────────────────

    @Test
    fun `аптечка создаётся и перечисляется`() {
        whenever(medKitService.create(userId)).thenReturn(medKit)
        whenever(medKitDrugOrchestrator.medKitSummaries(userId))
            .thenReturn(setOf(org.kert0n.medappserver.api.MedKitSummaryDTO(medKitId, 2, 17)))

        mockMvc.perform(post(ApiRoutes.MED_KITS).with(asUser()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(medKitId.toString()))

        mockMvc.perform(get(ApiRoutes.MED_KITS).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userCount").value(2))
            .andExpect(jsonPath("$[0].drugCount").value(17))
    }

    @Test
    fun `приглашение возвращается объектом, а не строкой`() {
        whenever(medKitService.invite(medKitId, userId)).thenReturn("invite-key")

        mockMvc.perform(post(ApiRoutes.invitations(medKitId)).with(asUser()))
            .andExpect(status().isCreated)
            // Объектом, а не строкой: рядом со строкой срок жизни добавить было бы некуда.
            .andExpect(jsonPath("$.key").value("invite-key"))
    }

    @Test
    fun `членство создаётся и удаляется`() {
        whenever(medKitService.joinByInvitation("invite-key", userId)).thenReturn(medKit)
        whenever(medKitDrugOrchestrator.medKitWithDrugs(medKitId, userId))
            .thenReturn(org.kert0n.medappserver.api.MedKitDTO(medKitId, emptySet(), 0))
        doNothing().whenever(medKitDrugOrchestrator).leaveMedKit(medKitId, userId, 0L)

        mockMvc.perform(
            post(ApiRoutes.MEMBERSHIPS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MembershipCreateRequest("invite-key")))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            delete(ApiRoutes.membership(medKitId)).with(asUser()).header(HttpHeaders.IF_MATCH, currentTag)
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `удаление аптечки принимает целевую параметром запроса`() {
        val target = UUID.randomUUID()
        doNothing().whenever(medKitDrugOrchestrator).delete(medKitId, userId, 0L, target)

        mockMvc.perform(
            delete(ApiRoutes.medKit(medKitId))
                .param("targetMedKitId", target.toString())
                .with(asUser())
                .header(HttpHeaders.IF_MATCH, currentTag)
        )
            .andExpect(status().isNoContent)
    }

    // ── Пользователь ─────────────────────────────────────────────────────────────

    @Test
    fun `снимок пользователя лежит по пути me`() {
        whenever(medKitDrugOrchestrator.medKitsWithDrugs(userId))
            .thenReturn(setOf(org.kert0n.medappserver.api.MedKitDTO(medKitId, setOf(drugDto), 0)))

        mockMvc.perform(get(ApiRoutes.ME).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.medKits[0].id").value(medKitId.toString()))
    }

    // ── Старой поверхности больше нет ────────────────────────────────────────────

    @Test
    fun `старые маршруты не обслуживаются`() {
        // Проверяется под аутентифицированным вызывающим: анонимный получил бы 401 от
        // security раньше маршрутизации, и это ничего не сказало бы о наличии маршрута.
        ApiRoutes.RETIRED.forEach { path ->
            mockMvc.perform(get(path).with(asUser())).andExpect(status().isNotFound)
        }
    }
}
