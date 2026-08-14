package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.MedKitContentView
import org.kert0n.medappserver.services.models.UserSnapshotView
import org.kert0n.medappserver.services.orchestrators.MedKitQueryService
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
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerTest {

    @MockitoBean
    private lateinit var queries: MedKitQueryService

    @Autowired
    private lateinit var context: WebApplicationContext

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
    fun `GET user data - returns user with medkits`() {
        whenever(queries.getUserSnapshot(userId))
            .thenReturn(UserSnapshotView(listOf(MedKitContentView(medKitId, emptyList()))))

        mockMvc.perform(
            get("/v1/users/me")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.medKits").isArray)
            .andExpect(jsonPath("$.medKits[0].id").value(medKitId.toString()))
    }

    @Test
    fun `GET user data - returns 401 without authentication`() {
        mockMvc.perform(get("/v1/users/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET user data - returns empty medkits for new user`() {
        whenever(queries.getUserSnapshot(userId)).thenReturn(UserSnapshotView(emptyList()))

        mockMvc.perform(
            get("/v1/users/me")
                .with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.medKits").isEmpty)
    }

    @Test
    fun `old user route is absent`() {
        mockMvc.perform(
            get("/v1/user").with(jwt().jwt { it.subject(userId.toString()) })
        ).andExpect(status().isNotFound)
    }
}
