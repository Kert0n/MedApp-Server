package org.kert0n.medappserver.controller

import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugServices
import org.kert0n.medappserver.testutil.qty
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerTest {

    @MockitoBean
    private lateinit var medKitDrugServices: MedKitDrugServices

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var medKitService: MedKitService

    private val userId = UUID.randomUUID()
    private val medKitId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `GET user data - returns user with medkits`() {
        val drugDTO = DrugDTO(
            id = UUID.randomUUID(), name = "Aspirin", quantity = qty(100.0),
            plannedQuantity = qty(0.0), quantityUnit = "mg", formType = null,
            category = null, manufacturer = null, country = null,
            description = null, medKitId = medKitId
        )
        val medKitDTO = MedKitDTO(id = medKitId, drugs = setOf(drugDTO))
        val medKits = listOf(org.kert0n.medappserver.db.model.MedKit(id = medKitId))
        whenever(medKitService.findAllByUser(userId)).thenReturn(medKits)
        whenever(medKitDrugServices.toMedKitDTO(any())).thenReturn(medKitDTO)

        mockMvc.perform(
            get("/user")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.medKits").isArray)
            .andExpect(jsonPath("$.medKits[0].id").value(medKitId.toString()))
    }

    @Test
    fun `GET user data - returns 401 without authentication`() {
        mockMvc.perform(get("/user"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET user data - returns empty medkits for new user`() {
        whenever(medKitService.findAllByUser(userId)).thenReturn(emptyList())

        mockMvc.perform(
            get("/user")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.medKits").isEmpty)
    }
}
