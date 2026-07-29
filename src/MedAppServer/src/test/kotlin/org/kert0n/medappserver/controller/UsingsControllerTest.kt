package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.TreatmentPlanCreateRequest
import org.kert0n.medappserver.api.TreatmentPlanPatchRequest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.services.models.PlanSnapshot
import org.kert0n.medappserver.services.models.TreatmentPlanView
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.IntakeOutcome
import org.kert0n.medappserver.services.orchestrators.IntakeService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.testutil.qty
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UsingsControllerTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @MockitoBean private lateinit var reads: UsingService
    @MockitoBean private lateinit var commands: TreatmentPlanService
    @MockitoBean private lateinit var intakes: IntakeService

    private lateinit var mockMvc: MockMvc
    private val userId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun plan(amount: Double = 30.0): Using {
        val user = User(id = userId, hashedKey = "key")
        val drug = Drug(
            id = drugId, name = "Drug", quantity = qty(100.0),
            quantityUnit = "mg", formType = null, category = null,
            manufacturer = null, country = null, description = null,
            medKit = MedKit()
        )
        return Using(UsingKey(userId, drugId), user, drug, qty(amount))
    }

    @Test
    fun `treatment plan resource supports CRUD`() {
        whenever(reads.listForUser(userId))
            .thenReturn(listOf(TreatmentPlanView(userId, drugId, qty(30.0))))
        whenever(reads.getForUser(userId, drugId))
            .thenReturn(TreatmentPlanView(userId, drugId, qty(30.0)))
        whenever(commands.create(userId, drugId, qty(30.0))).thenReturn(plan())
        whenever(commands.patch(userId, drugId, qty(50.0))).thenReturn(plan(50.0))
        doNothing().whenever(commands).delete(userId, drugId)

        mockMvc.perform(get("/v1/treatment-plans").with(jwtForUser()))
            .andExpect(status().isOk).andExpect(jsonPath("$[0].drugId").value(drugId.toString()))
        mockMvc.perform(get("/v1/treatment-plans/$drugId").with(jwtForUser()))
            .andExpect(status().isOk)
        mockMvc.perform(
            post("/v1/treatment-plans").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TreatmentPlanCreateRequest(drugId, qty(30.0))))
        ).andExpect(status().isCreated)
        mockMvc.perform(
            patch("/v1/treatment-plans/$drugId").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TreatmentPlanPatchRequest(qty(50.0))))
        ).andExpect(status().isOk).andExpect(jsonPath("$.plannedAmount").value(50.0))
        mockMvc.perform(delete("/v1/treatment-plans/$drugId").with(jwtForUser()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `intake id is in path and response represents nullable plan`() {
        val intakeId = UUID.randomUUID()
        whenever(intakes.record(userId, drugId, qty(10.0), intakeId))
            .thenReturn(IntakeOutcome(drugId, qty(10.0), null))

        mockMvc.perform(
            put("/v1/intakes/$intakeId").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(IntakeRequest(drugId, qty(10.0))))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            .andExpect(jsonPath("$.treatmentPlan").doesNotExist())
    }

    @Test
    fun `old using routes are absent`() {
        mockMvc.perform(get("/v1/using").with(jwtForUser())).andExpect(status().isNotFound)
        mockMvc.perform(get("/v1/using/drug/$drugId").with(jwtForUser()))
            .andExpect(status().isNotFound)
    }

    private fun jwtForUser() = jwt().jwt { it.subject(userId.toString()) }
    private fun json(value: Any) = objectMapper.writeValueAsString(value)
}
