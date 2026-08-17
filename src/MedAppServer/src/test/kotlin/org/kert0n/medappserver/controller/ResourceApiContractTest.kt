package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.TreatmentPlan
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.MedKitOverview
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.TreatmentPlanService
import org.kert0n.medappserver.services.models.CatalogueService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
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
import tools.jackson.databind.ObjectMapper
import java.util.*

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
    @MockitoBean private lateinit var treatmentPlanService: TreatmentPlanService
    @MockitoBean private lateinit var medKitService: MedKitService
    @MockitoBean private lateinit var medKitDrugOrchestrator: MedKitDrugOrchestrator
    @MockitoBean private lateinit var catalogueService: CatalogueService

    private lateinit var mockMvc: MockMvc

    private val userId: UUID = UUID.randomUUID()
    private val medKitId: UUID = UUID.randomUUID()
    private val drugId: UUID = UUID.randomUUID()

    private val medKit = MedKit(medKitId, setOf(userId))
    private val drug = Drug(
        id = drugId, medKitId = medKitId, name = "Aspirin",
        quantity = qty(100.0), quantityUnit = "mg"
    )

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
        whenever(drugService.require(drugId, userId)).thenReturn(drug)

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(drugId.toString()))
            .andExpect(jsonPath("$.medKitId").value(medKitId.toString()))
            // Доступный остаток приходит вместе с препаратом: раньше за ним ходили
            // отдельным запросом и могли увидеть два несогласованных момента времени.
            .andExpect(jsonPath("$.availableQuantity").exists())
    }

    @Test
    fun `препарат создаётся в аптечке из пути`() {
        whenever(medKitDrugOrchestrator.createDrugInMedKit(eq(medKitId), any(), eq(userId))).thenReturn(drug)
        whenever(drugService.require(drugId, userId)).thenReturn(drug)
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(100.0), quantityUnit = "mg")

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
        val body = DrugCreateRequest(name = "Aspirin", quantity = qty(0.0), quantityUnit = "mg")

        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `расход создаётся подчинённым ресурсом`() {
        whenever(drugService.consume(eq(drugId), any(), eq(userId))).thenReturn(drug)

        mockMvc.perform(
            post(ApiRoutes.consumptions(drugId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":2.0}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `перенос выражен размещением препарата в целевой аптечке`() {
        val target = UUID.randomUUID()
        whenever(medKitDrugOrchestrator.moveDrug(drugId, target, userId)).thenReturn(drug)
        whenever(drugService.require(drugId, userId)).thenReturn(drug)

        mockMvc.perform(put(ApiRoutes.drugIn(target, drugId)).with(asUser()))
            .andExpect(status().isOk)
    }

    @Test
    fun `препарат удаляется`() {
        doNothing().whenever(drugService).delete(drugId, userId)

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
        mockMvc.perform(
            get(ApiRoutes.DRUG_TEMPLATES).param("query", "аспир").param("limit", "500").with(asUser())
        )
            .andExpect(status().isBadRequest)
    }

    // ── Планы лечения ────────────────────────────────────────────────────────────

    @Test
    fun `план лечения создаётся и меняется`() {
        val plan = TreatmentPlan(userId = userId, drugId = drugId, plannedAmount = qty(20.0))
        whenever(drugService.createPlan(eq(userId), eq(drugId), any())).thenReturn(plan)
        whenever(drugService.changePlan(eq(userId), eq(drugId), any())).thenReturn(plan)
        whenever(treatmentPlanService.requirePlan(userId, drugId)).thenReturn(plan)

        mockMvc.perform(
            post(ApiRoutes.TREATMENT_PLANS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TreatmentPlanCreateRequest(drugId, qty(20.0))))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            // Времени создания и изменения в контракте больше нет.
            .andExpect(jsonPath("$.createdAt").doesNotExist())
            .andExpect(jsonPath("$.lastModified").doesNotExist())

        mockMvc.perform(
            patch(ApiRoutes.treatmentPlan(drugId)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TreatmentPlanPatchRequest(qty(15.0))))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `план лечения удаляется`() {
        doNothing().whenever(drugService).cancelPlan(userId, drugId)

        mockMvc.perform(delete(ApiRoutes.treatmentPlan(drugId)).with(asUser()))
            .andExpect(status().isNoContent)
    }

    // ── Приём ────────────────────────────────────────────────────────────────────

    @Test
    fun `приём опубликован, но пока не включён`() {
        // Форма маршрута финальная, механизм идемпотентности приезжает вместе с
        // версионностью. 501 честнее, чем PUT, который обещает идемпотентность и не даёт её.
        mockMvc.perform(
            put(ApiRoutes.intake(UUID.randomUUID())).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(IntakeRequest(drugId, qty(1.0))))
        )
            .andExpect(status().isNotImplemented)
    }

    // ── Аптечки и членство ───────────────────────────────────────────────────────

    @Test
    fun `аптечка создаётся и перечисляется`() {
        whenever(medKitService.createNew(userId)).thenReturn(medKit)
        whenever(medKitService.overviews(userId)).thenReturn(listOf(MedKitOverview(medKitId, 2, 17)))

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
        whenever(medKitService.generateMedKitShareKey(medKitId, userId)).thenReturn("invite-key")

        mockMvc.perform(post(ApiRoutes.invitations(medKitId)).with(asUser()))
            .andExpect(status().isCreated)
            // Строка в теле не оставляет места ничему рядом: добавить срок жизни позже
            // можно только сломав контракт.
            .andExpect(jsonPath("$.key").value("invite-key"))
    }

    @Test
    fun `членство создаётся и удаляется`() {
        whenever(medKitService.joinMedKitByKey("invite-key", userId)).thenReturn(medKit)
        whenever(medKitDrugOrchestrator.medKitWithDrugs(medKitId, userId))
            .thenReturn(org.kert0n.medappserver.api.MedKitDTO(medKitId, emptySet()))
        doNothing().whenever(medKitDrugOrchestrator).leaveMedKit(medKitId, userId)

        mockMvc.perform(
            post(ApiRoutes.MEMBERSHIPS).with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MembershipCreateRequest("invite-key")))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(delete(ApiRoutes.membership(medKitId)).with(asUser()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `удаление аптечки принимает целевую параметром запроса`() {
        val target = UUID.randomUUID()
        doNothing().whenever(medKitDrugOrchestrator).delete(medKitId, userId, target)

        mockMvc.perform(
            delete(ApiRoutes.medKit(medKitId)).param("targetMedKitId", target.toString()).with(asUser())
        )
            .andExpect(status().isNoContent)
    }

    // ── Пользователь ─────────────────────────────────────────────────────────────

    @Test
    fun `снимок пользователя лежит по пути me`() {
        whenever(medKitService.findAllByUser(userId)).thenReturn(listOf(medKit))
        whenever(drugService.accessibleTo(userId)).thenReturn(listOf(drug))

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
