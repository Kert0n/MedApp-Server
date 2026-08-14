package org.kert0n.medappserver.controller

import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.api.UsingDTO
import org.kert0n.medappserver.api.UsingUpdateDTO
import org.kert0n.medappserver.testutil.qty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.*
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.services.models.DrugService
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.*
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UsingsControllerTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var treatmentPlanService: TreatmentPlanService
    @MockitoBean
    private lateinit var usingService: UsingService

    @MockitoBean
    private lateinit var drugService: DrugService

    private val userId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun createTestUsing(): Using {
        val user = User(id = userId, hashedKey = "key")
        val medKit = MedKit(id = UUID.randomUUID())
        val drug = Drug(
            id = drugId, name = "Drug", quantity = qty(100.0),
            quantityUnit = "mg", formType = null, category = null,
            manufacturer = null, country = null, description = null,
            medKit = medKit
        )
        return Using(
            usingKey = UsingKey(userId, drugId),
            user = user,
            drug = drug,
            plannedAmount = qty(30.0)
        )
    }

    private fun createTestUsingDTO(): UsingDTO = UsingDTO(
        userId = userId,
        drugId = drugId,
        plannedAmount = qty(30.0)
    )

    @Test
    fun `GET all usings - returns list for authenticated user`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(usingService.findAllByUser(userId)).thenReturn(listOf(using))

        mockMvc.perform(
            get("/v1/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].drugId").value(drugId.toString()))
            .andExpect(jsonPath("$[0].plannedAmount").value(30.0))
    }

    @Test
    fun `GET all usings - returns 401 without authentication`() {
        mockMvc.perform(get("/v1/using"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET specific using - returns using for user and drug`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(usingService.findByUserAndDrugOrNull(userId, drugId)).thenReturn(using)

        mockMvc.perform(
            get("/v1/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            .andExpect(jsonPath("$.plannedAmount").value(30.0))
    }

    /**
     * Отсутствие плана — пустой ответ, а не 404.
     *
     * Тест заменил прежний, ждавший 404: тот закреплял поведение, разошедшееся с замыслом.
     * Tombstone'ов проект не ведёт, план исчезает сам — например когда приём забрал остаток
     * целиком, — и старый клиент законно приходит за уже удалённым. По 404 он не отличит
     * «плана нет» от «эндпоинт сломался» и будет повторять запрос. Тип `UsingDTO?` у метода
     * контроллера был рассчитан ровно на это, но `findByUserAndDrug` бросал, и ветка с null
     * стала недостижимой.
     */
    @Test
    fun `GET specific using - returns empty body when there is no plan`() {
        whenever(usingService.findByUserAndDrugOrNull(any(), any())).thenReturn(null)

        val body = mockMvc.perform(
            get("/v1/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(body.isBlank(), "тело должно быть пустым, получено: '$body'")
    }

    @Test
    fun `POST create using - creates and returns using`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(treatmentPlanService.create(eq(userId), any(), any())).thenReturn(using)

        val createDTO = UsingCreateDTO(drugId = drugId, plannedAmount = qty(30.0))

        mockMvc.perform(
            post("/v1/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createDTO))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.plannedAmount").value(30.0))
    }

    @Test
    fun `POST create using - returns 409 for duplicate`() {
        whenever(treatmentPlanService.create(eq(userId), any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.CONFLICT, "Already exists"))

        val createDTO = UsingCreateDTO(drugId = drugId, plannedAmount = qty(30.0))

        mockMvc.perform(
            post("/v1/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createDTO))
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `PUT update using - updates and returns using`() {
        val using = createTestUsing().apply { plannedAmount = qty(50.0) }
        val dto = createTestUsingDTO().copy(plannedAmount = qty(50.0))
        whenever(treatmentPlanService.update(eq(userId), eq(drugId), any())).thenReturn(using)

        val updateDTO = UsingUpdateDTO(plannedAmount = qty(50.0))

        mockMvc.perform(
            put("/v1/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDTO))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAmount").value(50.0))
    }

    @Test
    fun `POST record intake - records and returns using`() {
        val using = createTestUsing().apply { plannedAmount = qty(20.0) }
        val dto = createTestUsingDTO().copy(plannedAmount = qty(20.0))
        whenever(drugService.applyIntake(eq(userId), eq(drugId), eq(qty(10.0)))).thenReturn(using)

        val intakeRequest = IntakeRequest(quantityConsumed = qty(10.0), intakeId = UUID.randomUUID())

        mockMvc.perform(
            post("/v1/using/drug/$drugId/intake")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(intakeRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAmount").value(20.0))
    }

    @Test
    fun `POST record intake - returns 400 when exceeding planned amount`() {
        whenever(drugService.applyIntake(eq(userId), eq(drugId), any()))
            .thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Exceeds planned amount"))

        val intakeRequest = IntakeRequest(quantityConsumed = qty(100.0), intakeId = UUID.randomUUID())

        mockMvc.perform(
            post("/v1/using/drug/$drugId/intake")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(intakeRequest))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `DELETE treatment plan - returns 204`() {
        doNothing().whenever(usingService).deleteTreatmentPlan(userId, drugId)

        mockMvc.perform(
            delete("/v1/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNoContent)
    }

}
