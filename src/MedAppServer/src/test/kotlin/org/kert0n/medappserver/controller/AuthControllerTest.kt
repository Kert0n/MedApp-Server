package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.SecurityService
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var securityService: SecurityService

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        // SecurityService is mocked, so the token-request throttle would otherwise see the
        // default `false` and reject every /auth/login with 429 before authentication.
        // Throttling itself is covered by LoginThrottleTest.
        whenever(securityService.isLoginAllowed(any())).thenReturn(true)
        // Same reason: the registration secret comparison now goes through the service, and
        // an unstubbed mock would answer `false` for a correct secret too.
        whenever(securityService.secretsMatch(any(), any())).thenReturn(false)
        whenever(securityService.secretsMatch(eq("test-secret"), any())).thenReturn(true)
    }

    @Test
    fun `POST register - returns 403 with wrong secret`() {
        mockMvc.perform(
            post("/v1/auth/register")
                .header("X-Registration-Token", "wrong-secret")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST register - returns login and key with correct secret`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "hashed")
        whenever(securityService.validateRequest(any())).thenReturn(true)
        whenever(securityService.generateKey(32)).thenReturn("generated-key")
        whenever(userService.registerNewUser(any(), eq("generated-key"), any())).thenReturn(user)

        mockMvc.perform(
            post("/v1/auth/register")
                .header("X-Registration-Token", "test-secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("generated-key"))
    }

    @Test
    fun `POST register - returns 504 when rate limited`() {
        whenever(securityService.validateRequest(any())).thenReturn(false)

        mockMvc.perform(
            post("/v1/auth/register")
                .header("X-Registration-Token", "test-secret")
        )
            .andExpect(status().isGatewayTimeout)
    }

    @Test
    fun `GET login - returns token for authenticated user`() {
        val userId = UUID.randomUUID()
        val hashedPassword = "{noop}password"
        val user = User(id = userId, hashedKey = hashedPassword)
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)
        whenever(securityService.generateToken(any<User>(), any())).thenReturn("jwt-token-123")

        mockMvc.perform(
            get("/v1/auth/login")
                .with(httpBasic(userId.toString(), "password"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string("jwt-token-123"))
    }

    @Test
    fun `GET login - returns 401 with wrong password`() {
        val userId = UUID.randomUUID()
        val hashedPassword = "{noop}correct-password"
        val user = User(id = userId, hashedKey = hashedPassword)
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)

        mockMvc.perform(
            get("/v1/auth/login")
                .with(httpBasic(userId.toString(), "wrong-password"))
        )
            .andExpect(status().isUnauthorized)
    }
}
