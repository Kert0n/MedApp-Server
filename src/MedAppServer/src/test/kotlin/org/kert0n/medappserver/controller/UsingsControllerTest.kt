package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.*
import org.kert0n.medappserver.services.models.UsingService
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
import java.time.Instant
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UsingsControllerTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var usingService: UsingService

    private val userId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()
    private val now = Instant.now()

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
            id = drugId, name = "Drug", quantity = 100.0.toBigDecimal(),
            quantityUnit = "mg", formType = null, category = null,
            manufacturer = null, country = null, description = null,
            medKit = medKit
        )
        return Using(
            usingKey = UsingKey(userId, drugId),
            user = user,
            drug = drug,
            plannedAmount = 30.0.toBigDecimal(),
            createdAt = now,
            lastModified = now
        )
    }

    private fun createTestUsingDTO(): UsingDTO = UsingDTO(
        userId = userId,
        drugId = drugId,
        plannedAmount = 30.0.toBigDecimal(),
        createdAt = now,
        lastModified = now
    )

    @Test
    fun `GET all usings - returns list for authenticated user`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(usingService.findAllByUser(userId)).thenReturn(listOf(using))
        whenever(usingService.toUsingDTO(using)).thenReturn(dto)

        mockMvc.perform(
            get("/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].drugId").value(drugId.toString()))
            .andExpect(jsonPath("$[0].plannedAmount").value(30.0.toBigDecimal()))
    }

    @Test
    fun `GET all usings - returns 401 without authentication`() {
        mockMvc.perform(get("/using"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET specific using - returns using for user and drug`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(usingService.findByUserAndDrug(userId, drugId)).thenReturn(using)
        whenever(usingService.toUsingDTO(using)).thenReturn(dto)

        mockMvc.perform(
            get("/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drugId").value(drugId.toString()))
            .andExpect(jsonPath("$.plannedAmount").value(30.0.toBigDecimal()))
    }

    @Test
    fun `GET specific using - returns 404 when not found`() {
        whenever(usingService.findByUserAndDrug(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))

        mockMvc.perform(
            get("/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST create using - creates and returns using`() {
        val using = createTestUsing()
        val dto = createTestUsingDTO()
        whenever(usingService.createTreatmentPlan(eq(userId), any())).thenReturn(using)
        whenever(usingService.toUsingDTO(using)).thenReturn(dto)

        val createDTO = UsingCreateDTO(drugId = drugId, plannedAmount = 30.0.toBigDecimal())

        mockMvc.perform(
            post("/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createDTO))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.plannedAmount").value(30.0.toBigDecimal()))
    }

    @Test
    fun `POST create using - returns 409 for duplicate`() {
        whenever(usingService.createTreatmentPlan(eq(userId), any()))
            .thenThrow(ResponseStatusException(HttpStatus.CONFLICT, "Already exists"))

        val createDTO = UsingCreateDTO(drugId = drugId, plannedAmount = 30.0.toBigDecimal())

        mockMvc.perform(
            post("/using")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createDTO))
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `PUT update using - updates and returns using`() {
        val using = createTestUsing().apply { plannedAmount = 50.0.toBigDecimal() }
        val dto = createTestUsingDTO().copy(plannedAmount = 50.0.toBigDecimal())
        whenever(usingService.updateTreatmentPlan(eq(userId), eq(drugId), any())).thenReturn(using)
        whenever(usingService.toUsingDTO(using)).thenReturn(dto)

        val updateDTO = UsingUpdateDTO(plannedAmount = 50.0.toBigDecimal())

        mockMvc.perform(
            put("/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDTO))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAmount").value(50.0.toBigDecimal()))
    }

    @Test
    fun `POST record intake - records and returns using`() {
        val using = createTestUsing().apply { plannedAmount = 20.0.toBigDecimal() }
        val dto = createTestUsingDTO().copy(plannedAmount = 20.0.toBigDecimal())
        whenever(usingService.recordIntake(eq(userId), eq(drugId), eq(10.0.toBigDecimal()))).thenReturn(using)
        whenever(usingService.toUsingDTO(using)).thenReturn(dto)

        val intakeRequest = IntakeRequest(quantityConsumed = 10.0.toBigDecimal())

        mockMvc.perform(
            post("/using/drug/$drugId/intake")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(intakeRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAmount").value(20.0.toBigDecimal()))
    }

    @Test
    fun `POST record intake - returns 400 when exceeding planned amount`() {
        whenever(usingService.recordIntake(eq(userId), eq(drugId), any()))
            .thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Exceeds planned amount"))

        val intakeRequest = IntakeRequest(quantityConsumed = 100.0.toBigDecimal())

        mockMvc.perform(
            post("/using/drug/$drugId/intake")
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
            delete("/using/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNoContent)
    }
}
