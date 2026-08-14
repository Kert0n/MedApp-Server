package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.DrugConsumptionRequest
import org.kert0n.medappserver.api.DrugCreateDTO
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.VidalDrugService
import org.kert0n.medappserver.services.models.toView
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
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
class DrugControllerTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @MockitoBean private lateinit var reads: DrugService
    @MockitoBean private lateinit var commands: DrugCommandService
    @MockitoBean private lateinit var catalog: VidalDrugService

    private lateinit var mockMvc: MockMvc
    private val userId = UUID.randomUUID()
    private val drugId = UUID.randomUUID()
    private val medKitId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun drug(quantity: Double = 100.0) = Drug(
        id = drugId,
        name = "Aspirin",
        quantity = qty(quantity),
        totalPlannedAmount = qty(30.0),
        quantityUnit = "mg",
        formType = null,
        category = null,
        manufacturer = null,
        country = null,
        description = null,
        medKit = MedKit(id = medKitId)
    )

    @Test
    fun `resource routes expose drug commands and derived quantities`() {
        whenever(reads.getAccessible(userId, drugId)).thenReturn(drug().toView())
        whenever(commands.create(eq(userId), eq(medKitId), any())).thenReturn(drug())
        whenever(commands.patch(eq(userId), eq(drugId), any())).thenReturn(drug())
        whenever(commands.consume(userId, drugId, qty(10.0))).thenReturn(drug(90.0))
        whenever(commands.move(userId, drugId, medKitId)).thenReturn(drug())
        doNothing().whenever(commands).delete(userId, drugId)

        mockMvc.perform(get("/v1/drugs/$drugId").with(jwtForUser()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.availableQuantity").value(70.0))

        mockMvc.perform(
            post("/v1/med-kits/$medKitId/drugs").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(DrugCreateDTO("Aspirin", qty(100.0), "mg")))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            patch("/v1/drugs/$drugId").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(DrugPatchRequest(name = "Corrected")))
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/v1/drugs/$drugId/consumptions").with(jwtForUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(DrugConsumptionRequest(qty(10.0))))
        ).andExpect(status().isOk).andExpect(jsonPath("$.quantity").value(90.0))

        mockMvc.perform(put("/v1/med-kits/$medKitId/drugs/$drugId").with(jwtForUser()))
            .andExpect(status().isOk)

        mockMvc.perform(delete("/v1/drugs/$drugId").with(jwtForUser()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `catalog uses query parameter and resource path`() {
        val templateId = UUID.randomUUID()
        val template = VidalDrug(
            id = templateId,
            name = "Aspirin",
            manufacturer = "Bayer",
            otc = true
        )
        whenever(catalog.fuzzySearch("asp", 10)).thenReturn(listOf(template))
        whenever(catalog.findById(templateId)).thenReturn(template)

        mockMvc.perform(
            get("/v1/drug-templates").with(jwtForUser()).param("query", "asp")
        ).andExpect(status().isOk).andExpect(jsonPath("$[0].name").value("Aspirin"))

        mockMvc.perform(get("/v1/drug-templates/$templateId").with(jwtForUser()))
            .andExpect(status().isOk)
    }

    @Test
    fun `old drug routes are absent`() {
        mockMvc.perform(get("/v1/drug/$drugId").with(jwtForUser()))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/v1/drug/template/search").with(jwtForUser()).param("searchTerm", "asp"))
            .andExpect(status().isNotFound)
    }

    private fun jwtForUser() = jwt().jwt { it.subject(userId.toString()) }
    private fun json(value: Any) = objectMapper.writeValueAsString(value)
}
