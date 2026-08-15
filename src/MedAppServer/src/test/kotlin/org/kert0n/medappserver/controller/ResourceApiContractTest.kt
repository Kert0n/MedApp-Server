package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.MedKitContentView
import org.kert0n.medappserver.application.model.MedKitSummaryView
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.kert0n.medappserver.application.model.UserSnapshotView
import org.kert0n.medappserver.application.orchestrator.DrugOrchestrator
import org.kert0n.medappserver.application.orchestrator.IntakeOrchestrator
import org.kert0n.medappserver.application.orchestrator.MedKitOrchestrator
import org.kert0n.medappserver.application.orchestrator.TreatmentPlanOrchestrator
import org.kert0n.medappserver.application.query.CatalogueQueryService
import org.kert0n.medappserver.application.query.DrugQueryService
import org.kert0n.medappserver.application.query.MedKitQueryService
import org.kert0n.medappserver.application.query.TreatmentPlanQueryService
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class ResourceApiContractTest {
    @Autowired private lateinit var context: WebApplicationContext
    @MockitoBean private lateinit var drugQueries: DrugQueryService
    @MockitoBean private lateinit var planQueries: TreatmentPlanQueryService
    @MockitoBean private lateinit var medKitQueries: MedKitQueryService
    @MockitoBean private lateinit var catalogueQueries: CatalogueQueryService
    @MockitoBean private lateinit var drugCommands: DrugOrchestrator
    @MockitoBean private lateinit var planCommands: TreatmentPlanOrchestrator
    @MockitoBean private lateinit var intakeCommands: IntakeOrchestrator
    @MockitoBean private lateinit var medKitCommands: MedKitOrchestrator

    private lateinit var mockMvc: MockMvc
    private val userId = UUID.randomUUID()
    private val medKitId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `resource read routes return immutable DTOs`() {
        val drug = drugResult()
        val plan = TreatmentPlanResult(userId, drugId, BigDecimal("2.000000"))
        val content = MedKitContentView(medKitId, listOf(drug))
        whenever(drugQueries.getAccessible(userId, drugId)).thenReturn(drug)
        whenever(planQueries.listForUser(userId)).thenReturn(listOf(plan))
        whenever(planQueries.getForUser(userId, drugId)).thenReturn(plan)
        whenever(medKitQueries.listForUser(userId)).thenReturn(listOf(MedKitSummaryView(medKitId, 1, 1)))
        whenever(medKitQueries.getContent(userId, medKitId)).thenReturn(content)
        whenever(medKitQueries.getUserSnapshot(userId)).thenReturn(UserSnapshotView(userId, listOf(content)))

        listOf(
            "/v1/drugs/$drugId",
            "/v1/treatment-plans",
            "/v1/treatment-plans/$drugId",
            "/v1/med-kits",
            "/v1/med-kits/$medKitId",
            "/v1/users/me"
        ).forEach { path ->
            mockMvc.perform(get(path).with(jwt().jwt { it.subject(userId.toString()) }))
                .andExpect(status().isOk)
        }

        mockMvc.perform(get("/v1/drugs/$drugId").with(jwt().jwt { it.subject(userId.toString()) }))
            .andExpect(jsonPath("$.availableQuantity").value(8.0))
    }

    @Test
    fun `protected resource route rejects anonymous request`() {
        mockMvc.perform(get("/v1/users/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `legacy routes have no aliases`() {
        listOf("/drug/$drugId", "/using", "/med-kit", "/user").forEach { path ->
            mockMvc.perform(get(path).with(jwt().jwt { it.subject(userId.toString()) }))
                .andExpect(status().isNotFound)
        }
    }

    @Test
    fun `DTO descriptions are published in OpenAPI`() {
        val yaml = mockMvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        listOf(
            "DrugCreateRequest:",
            "DrugPatchRequest:",
            "TreatmentPlanDTO:",
            "IntakeResultDTO:",
            "MedKitInvitationDTO:",
            "description:"
        ).forEach { expected -> check(expected in yaml) { "OpenAPI misses $expected" } }
    }

    private fun drugResult(): DrugResult = DrugResult(
        id = drugId,
        medKitId = medKitId,
        name = "Drug",
        quantity = BigDecimal.TEN,
        plannedQuantity = BigDecimal("2.000000"),
        availableQuantity = BigDecimal("8.000000"),
        quantityUnit = "tablet",
        formType = null,
        category = null,
        manufacturer = null,
        country = null,
        description = null
    )
}
