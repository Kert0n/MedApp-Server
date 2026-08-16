package org.kert0n.medappserver.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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
        // default `false` and reject every token request with 429 before authentication.
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
            post(ApiRoutes.REGISTER)
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
            post(ApiRoutes.REGISTER)
                .header("X-Registration-Token", "test-secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("generated-key"))
    }

    @Test
    fun `POST register - returns 429 when rate limited`() {
        whenever(securityService.validateRequest(any())).thenReturn(false)

        mockMvc.perform(
            post(ApiRoutes.REGISTER)
                .header("X-Registration-Token", "test-secret")
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Too many requests"))
    }

    @Test
    fun `POST token - returns the token`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)
        whenever(securityService.generateToken(any<User>(), any())).thenReturn("jwt-token-123")

        mockMvc.perform(
            post(ApiRoutes.TOKEN)
                .with(httpBasic(userId.toString(), "password"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").value("jwt-token-123"))
            // Срока жизни в ответе нет намеренно: он уже в claim exp самого токена.
            .andExpect(jsonPath("$.expiresIn").doesNotExist())
    }

    @Test
    fun `POST token - returns 401 with wrong password`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}correct-password")
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)

        mockMvc.perform(
            post(ApiRoutes.TOKEN)
                .with(httpBasic(userId.toString(), "wrong-password"))
        )
            .andExpect(status().isUnauthorized)
            // Отказ безопасности отвечает тем же форматом, что и ошибки контроллеров.
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Authentication is required"))
    }

    @Test
    fun `старый маршрут выдачи токена больше не существует`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)

        // Basic здесь уже не принимается: цепочка выдачи токена слушает только новый путь.
        mockMvc.perform(get("/auth/login").with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/auth/register").header("X-Registration-Token", "test-secret"))
            .andExpect(status().isUnauthorized)
    }
}
