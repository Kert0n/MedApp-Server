package org.kert0n.medappserver.controller

import org.kert0n.medappserver.api.JoinMedKitRequest
import org.kert0n.medappserver.api.MedKitDTO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.MedKitContentView
import org.kert0n.medappserver.services.models.MedKitSummaryView
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MedKitControllerTest {

    @MockitoBean
    private lateinit var lifecycle: MedKitLifecycleService

    @MockitoBean
    private lateinit var queries: MedKitQueryService

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    private val userId = UUID.randomUUID()
    private val medKitId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `POST create medkit - returns 201 with id`() {
        whenever(lifecycle.create(userId)).thenReturn(medKitId)

        mockMvc.perform(
            post("/v1/med-kit")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(medKitId.toString()))
    }

    @Test
    fun `POST create medkit - returns 401 without authentication`() {
        mockMvc.perform(post("/v1/med-kit"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET medkit by id - returns medkit DTO`() {
        whenever(queries.getContent(userId, medKitId))
            .thenReturn(MedKitContentView(medKitId, emptyList()))

        mockMvc.perform(
            get("/v1/med-kit/$medKitId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(medKitId.toString()))
    }

    @Test
    fun `GET medkit by id - returns 404 for unauthorized user`() {
        whenever(queries.getContent(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"))

        mockMvc.perform(
            get("/v1/med-kit/$medKitId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET all medkits - returns summary list`() {
        val summaries = setOf(
            MedKitSummaryView(medKitId, 2, 5),
            MedKitSummaryView(UUID.randomUUID(), 1, 3)
        )
        whenever(queries.listForUser(userId)).thenReturn(summaries)

        mockMvc.perform(
            get("/v1/med-kit")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].id").value(medKitId.toString()))
            .andExpect(jsonPath("$[0].userCount").value(2))
            .andExpect(jsonPath("$[0].drugCount").value(5))
    }

    @Test
    fun `POST share medkit - returns share key`() {
        whenever(lifecycle.createInvitation(userId, medKitId)).thenReturn("share-key-123")

        mockMvc.perform(
            post("/v1/med-kit/$medKitId/share")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(content().string("share-key-123"))
    }

    @Test
    fun `POST join medkit - returns medkit DTO`() {
        whenever(lifecycle.join(userId, "share-key-123")).thenReturn(medKitId)
        whenever(queries.getContent(userId, medKitId))
            .thenReturn(MedKitContentView(medKitId, emptyList()))

        val joinRequest = JoinMedKitRequest(key = "share-key-123")

        mockMvc.perform(
            post("/v1/med-kit/join")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(joinRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(medKitId.toString()))
    }

    @Test
    fun `POST join medkit - rejects blank key`() {
        val joinRequest = JoinMedKitRequest(key = "")

        mockMvc.perform(
            post("/v1/med-kit/join")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(joinRequest))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `DELETE leave medkit - returns 204`() {
        doNothing().whenever(lifecycle).leave(userId, medKitId)

        mockMvc.perform(
            delete("/v1/med-kit/$medKitId/leave")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE medkit - returns 204`() {
        doNothing().whenever(lifecycle).delete(userId, medKitId, null)

        mockMvc.perform(
            delete("/v1/med-kit/$medKitId")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE medkit with transfer - returns 204`() {
        val transferId = UUID.randomUUID()
        doNothing().whenever(lifecycle).delete(userId, medKitId, transferId)

        mockMvc.perform(
            delete("/v1/med-kit/$medKitId")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .param("transferToMedKitId", transferId.toString())
        )
            .andExpect(status().isNoContent)
    }
}
