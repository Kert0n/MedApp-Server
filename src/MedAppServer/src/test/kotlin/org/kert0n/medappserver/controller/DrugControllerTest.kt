package org.kert0n.medappserver.controller

import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.kert0n.medappserver.testutil.qty
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DrugControllerTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var drugService: DrugService

    @MockitoBean
    private lateinit var medKitDrugServices: MedKitDrugServices

    @MockitoBean
    private lateinit var usingService: UsingService

    @MockitoBean
    private lateinit var vidalDrugService: VidalDrugService

    private val userId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()
    private val medKitId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun createTestMedKit(): MedKit = MedKit(id = medKitId)

    private fun createTestDrug(): Drug = Drug(
        id = drugId,
        name = "Aspirin",
        quantity = qty(100.0),
        quantityUnit = "mg",
        formType = "tablet",
        category = "painkiller",
        manufacturer = "Bayer",
        country = "Germany",
        description = "Pain relief",
        medKit = createTestMedKit()
    )

    private fun createTestDrugDTO(): DrugDTO = DrugDTO(
        id = drugId,
        name = "Aspirin",
        quantity = qty(100.0),
        plannedQuantity = qty(30.0),
        quantityUnit = "mg",
        formType = "tablet",
        category = "painkiller",
        manufacturer = "Bayer",
        country = "Germany",
        description = "Pain relief",
        medKitId = medKitId
    )

    @Test
    fun `GET drug by id - returns drug for authenticated user`() {
        val drug = createTestDrug()
        val dto = createTestDrugDTO()
        whenever(drugService.findByIdForUser(drugId, userId)).thenReturn(drug)
        whenever(drugService.toDrugDTO(drug)).thenReturn(dto)

        mockMvc.perform(
            get("/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(drugId.toString()))
            .andExpect(jsonPath("$.name").value("Aspirin"))
            .andExpect(jsonPath("$.quantity").value(100.0))
            .andExpect(jsonPath("$.plannedQuantity").value(30.0))
    }

    @Test
    fun `GET drug by id - returns 404 when not found`() {
        whenever(drugService.findByIdForUser(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found"))

        mockMvc.perform(
            get("/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET drug by id - returns 401 without authentication`() {
        mockMvc.perform(get("/drug/$drugId"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST create drug - creates and returns drug`() {
        val drug = createTestDrug()
        val dto = createTestDrugDTO()
        whenever(medKitDrugServices.createDrugInMedkit(any(), eq(userId))).thenReturn(drug)
        whenever(drugService.toDrugDTO(drug)).thenReturn(dto)

        val createDTO = DrugCreateDTO(
            name = "Aspirin",
            quantity = qty(100.0),
            quantityUnit = "mg",
            medKitId = medKitId
        )

        mockMvc.perform(
            post("/drug")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createDTO))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Aspirin"))
    }

    @Test
    fun `PUT update drug - updates and returns drug`() {
        val drug = createTestDrug()
        val dto = createTestDrugDTO()
        whenever(drugService.update(eq(drugId), any(), eq(userId))).thenReturn(drug)
        whenever(drugService.toDrugDTO(drug)).thenReturn(dto)

        val updateDTO = DrugUpdateDTO(name = "Updated Aspirin")

        mockMvc.perform(
            put("/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateDTO))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Aspirin"))
    }

    @Test
    fun `DELETE drug - returns 204`() {
        doNothing().whenever(drugService).delete(drugId, userId)

        mockMvc.perform(
            delete("/drug/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET quantity info - returns quantity info`() {
        val drug = createTestDrug()
        whenever(drugService.findByIdForUser(drugId, userId)).thenReturn(drug)
        drug.totalPlannedAmount = qty(30.0)

        mockMvc.perform(
            get("/drug/quantity/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actualQuantity").value(100.0))
            .andExpect(jsonPath("$.plannedQuantity").value(30.0))
            .andExpect(jsonPath("$.availableQuantity").value(70.0))
    }

    @Test
    fun `PUT consume drug - reduces quantity and returns drug`() {
        val drug = createTestDrug().apply { quantity = qty(90.0) }
        val dto = createTestDrugDTO().copy(quantity = qty(90.0))
        whenever(drugService.consumeDrug(eq(drugId), eq(qty(10.0)), eq(userId))).thenReturn(drug)
        whenever(drugService.toDrugDTO(drug)).thenReturn(dto)

        val consumeRequest = ConsumeRequest(quantity = qty(10.0))

        mockMvc.perform(
            put("/drug/consume/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(consumeRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quantity").value(90.0))
    }

    @Test
    fun `PUT move drug - moves drug and returns it`() {
        val targetMedKitId = UUID.randomUUID()
        val drug = createTestDrug()
        val dto = createTestDrugDTO()
        whenever(medKitDrugServices.moveDrug(eq(drugId), eq(targetMedKitId), eq(userId))).thenReturn(drug)
        whenever(drugService.toDrugDTO(drug)).thenReturn(dto)

        val moveRequest = MoveDrugRequest(targetMedKitId = targetMedKitId)

        mockMvc.perform(
            put("/drug/move/$drugId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(moveRequest))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `GET template search - returns matching templates`() {
        val vd = VidalDrug(
            id = UUID.randomUUID(),
            name = "Aspirin",
            manufacturer = "Bayer",
            otc = true
        )
        whenever(vidalDrugService.fuzzySearch("asp", 10)).thenReturn(listOf(vd))

        mockMvc.perform(
            get("/drug/template/search")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .param("searchTerm", "asp")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Aspirin"))
    }

    @Test
    fun `GET template search - returns matching templates for Cyrillic search term`() {
        val vd = VidalDrug(
            id = UUID.randomUUID(),
            name = "Аспирин",
            manufacturer = "Байер",
            otc = true
        )
        whenever(vidalDrugService.fuzzySearch("аспир", 10)).thenReturn(listOf(vd))

        mockMvc.perform(
            get("/drug/template/search")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .param("searchTerm", "аспир")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Аспирин"))
    }

    @Test
    fun `GET template by id - returns template`() {
        val templateId = UUID.randomUUID()
        val vd = VidalDrug(
            id = templateId,
            name = "Aspirin",
            manufacturer = "Bayer",
            otc = true
        )
        whenever(vidalDrugService.findById(templateId)).thenReturn(vd)

        mockMvc.perform(
            get("/drug/template/$templateId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Aspirin"))
    }

    @Test
    fun `GET template by id - returns 404 when not found`() {
        val templateId = UUID.randomUUID()
        whenever(vidalDrugService.findById(templateId)).thenReturn(null)

        mockMvc.perform(
            get("/drug/template/$templateId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNotFound)
    }
}
