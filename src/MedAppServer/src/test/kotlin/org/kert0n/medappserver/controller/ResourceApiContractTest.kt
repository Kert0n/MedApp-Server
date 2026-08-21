package org.kert0n.medappserver.controller

import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.api.toSnapshot
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.CatalogueService
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.application.ReservationApplicationService
import org.kert0n.medappserver.services.application.UserApplicationService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.qty
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
    // Тем же Json, что читает сервер: тело запроса в тесте должно быть тем же
    // текстом, что придёт с клиента, иначе проверяется не тот провод.
    @Autowired private lateinit var json: Json

    // Подменяются ровно те, кого зовёт контроллер: по одному прикладному сервису на ресурс.
    // Раньше здесь стояли и сервисы агрегатов — контроллер ходил и туда тоже.
    @MockitoBean private lateinit var drugs: DrugApplicationService
    @MockitoBean private lateinit var reservations: ReservationApplicationService
    @MockitoBean private lateinit var medKits: MedKitApplicationService
    @MockitoBean private lateinit var users: UserApplicationService
    @MockitoBean private lateinit var catalogueService: CatalogueService

    private lateinit var mockMvc: MockMvc

    private val userId: Uuid = Uuid.random()
    private val medKitId: Uuid = Uuid.random()
    private val drugId: Uuid = Uuid.random()

    private val medKit = MedKit(medKitId, setOf(userId))
    private val unit = QuantityUnit(Uuid.random(), "mg")
    private val drug = Drug(
        id = drugId, medKitId = medKitId, name = "Aspirin",
        quantity = Quantity(qty(100.0), unit)
    )
    private val drugDto = drug.toDto()
    private val snapshot = drug.toSnapshot(emptyList(), userId)

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
        whenever(drugs.read(drugId, userId)).thenReturn(snapshot)

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))
            .andExpect(jsonPath("$.drug.medKitId").value(medKitId.toString()))
            // Заявленное отделено от самой пачки: сумма может превышать остаток, а своя доля
            // показывается отдельно — по ней клиент рисует «сколько из этого моё».
            .andExpect(jsonPath("$.reservations.total").exists())
            .andExpect(jsonPath("$.drug.reservedQuantity").doesNotExist())
            .andExpect(jsonPath("$.drug.quantityUnit").doesNotExist())
    }

    @Test
    fun `препарат создаётся в аптечке из пути`() {
        whenever(drugs.createInMedKit(eq(medKitId), any(), eq(userId))).thenReturn(snapshot)
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnitId = unit.id)

        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.encodeToString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))
    }

    @Test
    fun `нулевое количество при создании отвергается`() {
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(0.0), quantityUnitId = unit.id)

        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.encodeToString(body))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `приём создаётся подчинённым ресурсом упаковки`() {
        whenever(drugs.recordIntake(eq(drugId), any(), eq(userId))).thenReturn(snapshot)

        mockMvc.perform(
            post(ApiRoutes.intakes(drugId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":"2.0"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))
    }

    @Test
    fun `перенос выражен размещением препарата в целевой аптечке`() {
        val target = Uuid.random()
        whenever(drugs.moveToMedKit(drugId, target, userId)).thenReturn(snapshot)

        mockMvc.perform(put(ApiRoutes.drugIn(target, drugId)).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))
    }

    @Test
    fun `препарат удаляется`() {
        doNothing().whenever(drugs).delete(drugId, userId)

        mockMvc.perform(delete(ApiRoutes.drug(drugId)).with(asUser()))
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
        mockMvc.perform(get(ApiRoutes.DRUG_TEMPLATES).param("query", "аспир").param("limit", "500").with(asUser()))
            .andExpect(status().isBadRequest)
    }

    // ── Брони ────────────────────────────────────────────────────────────────────

    @Test
    fun `план лечения создаётся и меняется`() {
        val reservation = Reservation(userId = userId, drugId = drugId, amount = Quantity(qty(20.0), unit)).toDto()
        whenever(reservations.create(eq(userId), eq(drugId), any())).thenReturn(reservation)
        whenever(reservations.changeTo(eq(userId), eq(drugId), any())).thenReturn(reservation)
        whenever(reservations.read(userId, drugId)).thenReturn(reservation)

        mockMvc.perform(
            post(ApiRoutes.RESERVATIONS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.encodeToString(ReservationCreateRequest(drugId, qty(20.0))))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            // Отметок времени в контракте нет.
            .andExpect(jsonPath("$.createdAt").doesNotExist())
            .andExpect(jsonPath("$.lastModified").doesNotExist())

        mockMvc.perform(
            patch(ApiRoutes.reservation(drugId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.encodeToString(ReservationPatchRequest(qty(15.0))))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `план лечения удаляется`() {
        doNothing().whenever(reservations).cancel(userId, drugId)

        mockMvc.perform(delete(ApiRoutes.reservation(drugId)).with(asUser()))
            .andExpect(status().isNoContent)
    }

    // ── Аптечки и членство ───────────────────────────────────────────────────────

    @Test
    fun `аптечка создаётся и перечисляется`() {
        whenever(medKits.create(userId)).thenReturn(MedKitCreatedDTO(medKitId))
        whenever(medKits.summaries(userId))
            .thenReturn(setOf(MedKitSummaryDTO(medKitId, 2, setOf(drugId))))

        mockMvc.perform(post(ApiRoutes.MED_KITS).with(asUser()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(medKitId.toString()))

        mockMvc.perform(get(ApiRoutes.MED_KITS).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userCount").value(2))
            .andExpect(jsonPath("$[0].drugIds[0]").value(drugId.toString()))
    }

    @Test
    fun `приглашение возвращается объектом, а не строкой`() {
        whenever(medKits.invite(medKitId, userId)).thenReturn(InvitationDTO("invite-key"))

        mockMvc.perform(post(ApiRoutes.invitations(medKitId)).with(asUser()))
            .andExpect(status().isCreated)
            // Объектом, а не строкой: рядом со строкой срок жизни добавить было бы некуда.
            .andExpect(jsonPath("$.key").value("invite-key"))
    }

    @Test
    fun `членство создаётся и удаляется`() {
        whenever(medKits.joinByInvitation("invite-key", userId))
            .thenReturn(MedKitDTO(medKitId, 2, emptySet()))
        doNothing().whenever(medKits).leave(medKitId, userId)

        mockMvc.perform(
            post(ApiRoutes.MEMBERSHIPS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.encodeToString(MembershipCreateRequest("invite-key")))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(delete(ApiRoutes.membership(medKitId)).with(asUser()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `удаление аптечки принимает целевую параметром запроса`() {
        val target = Uuid.random()
        doNothing().whenever(medKits).delete(medKitId, userId, target)

        mockMvc.perform(
            delete(ApiRoutes.medKit(medKitId))
                .param("targetMedKitId", target.toString())
                .with(asUser())
        )
            .andExpect(status().isNoContent)
    }

    // ── Пользователь ─────────────────────────────────────────────────────────────

    @Test
    fun `снимок пользователя лежит по пути me`() {
        whenever(users.snapshot(userId))
            .thenReturn(UserSnapshotDTO(userId, setOf(MedKitDTO(medKitId, 2, setOf(snapshot)))))

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
