package org.kert0n.medappserver.controller

import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.domain.UserAlreadyExists
import org.kert0n.medappserver.services.aggregate.UserService
import org.kert0n.medappserver.services.security.AuthenticatedUserService
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var authenticatedUserService: AuthenticatedUserService

    @MockitoBean
    private lateinit var securityService: SecurityService

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        // `SecurityService` — мок: без заглушки ограничитель видит умолчание `false` и
        // отвергает каждый запрос токена с 429. Сам ограничитель проверяет LoginThrottleTest.
        whenever(securityService.isLoginAllowed(any())).thenReturn(true)
        // По той же причине: сравнение секрета идёт через сервис, и мок без заглушки отвечает
        // `false` даже на верный секрет.
        whenever(securityService.secretsMatch(any(), any())).thenReturn(false)
        whenever(securityService.secretsMatch(eq("test-secret"), any())).thenReturn(true)
    }

    @Test
    fun `POST register - returns 403 with wrong secret`() {
        register(secret = "wrong-secret")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST register - stores the credentials chosen by the client`() {
        val login = Uuid.random()
        whenever(securityService.validateRequest(any())).thenReturn(true)
        whenever(userService.registerNewUser(any(), any(), any())).thenReturn(User(id = login, hashedKey = "hashed"))

        register(login = login)
            .andExpect(status().isCreated)
            // Возвращать нечего: логин и пароль клиент знает сам, иначе он не прислал бы их.
            .andExpect(content().string(""))

        verify(userService).registerNewUser(eq(login), eq(PASSWORD), any())
    }

    @Test
    fun `POST register - returns 409 when the login is taken`() {
        whenever(securityService.validateRequest(any())).thenReturn(true)
        whenever(userService.registerNewUser(any(), any(), any())).thenThrow(UserAlreadyExists())

        register()
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }

    @Test
    fun `POST register - rejects a short or non-ASCII password`() {
        whenever(securityService.validateRequest(any())).thenReturn(true)

        register(password = "k".repeat(31)).andExpect(status().isBadRequest)
        register(password = "k".repeat(73)).andExpect(status().isBadRequest)
        // Кириллица: символов в пределах, байтов у bcrypt было бы вдвое больше.
        register(password = "ключ".repeat(10)).andExpect(status().isBadRequest)
        register(password = "k".repeat(20) + " " + "k".repeat(20)).andExpect(status().isBadRequest)

        verify(userService, never()).registerNewUser(any(), any(), any())
    }

    @Test
    fun `POST register - returns 429 when rate limited`() {
        whenever(securityService.validateRequest(any())).thenReturn(false)

        register()
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Too many requests"))
    }

    @Test
    fun `POST token - returns the token`() {
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)
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
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}correct-password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

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
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        // Basic здесь не принимается: цепочка выдачи токена слушает только `/v1/auth/token`.
        mockMvc.perform(get("/auth/login").with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/auth/register").header("X-Registration-Token", "test-secret"))
            .andExpect(status().isUnauthorized)
    }

    private fun register(
        secret: String = "test-secret",
        login: Uuid = Uuid.random(),
        password: String = PASSWORD
    ) = mockMvc.perform(
        post(ApiRoutes.REGISTER)
            .header("X-Registration-Token", secret)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"login":"$login","password":"$password"}""")
    )

    private companion object {
        const val PASSWORD = "0123456789abcdefghijklmnopqrstuvwxyz-_ABCD"
    }
}
