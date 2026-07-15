package org.kert0n.medappserver.controller

import com.sksamuel.aedile.core.Cache
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

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

    @Autowired
    private lateinit var successfulRegistrationsCache: Cache<String, AtomicInteger>

    @BeforeEach
    fun setup() {
        successfulRegistrationsCache.invalidateAll()
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    private fun register(token: String): ResultActions {
        val asyncResult = mockMvc.perform(
            post("/auth/register")
                .header("X-Registration-Token", token)
        )
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(asyncResult))
    }

    @Test
    fun `POST register - returns 403 with wrong secret`() {
        register("wrong-secret")
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST register - returns login and key with correct secret`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "hashed")
        whenever(securityService.generateKey(32)).thenReturn("generated-key")
        whenever(userService.registerNewUser(any(), eq("generated-key"))).thenReturn(user)

        register("test-secret")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.key").value("generated-key"))
    }

    @Test
    fun `POST register - returns 429 when rate limited`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "hashed")
        whenever(securityService.generateKey(32)).thenReturn("generated-key")
        whenever(userService.registerNewUser(any(), eq("generated-key"))).thenReturn(user)

        register("test-secret")
            .andExpect(status().isCreated)

        register("test-secret")
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `GET login - returns token for authenticated user`() {
        val userId = UUID.randomUUID()
        val hashedPassword = "{noop}password"
        val user = User(id = userId, hashedKey = hashedPassword)
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)
        whenever(securityService.generateToken(any<User>(), any())).thenReturn("jwt-token-123")

        mockMvc.perform(
            get("/auth/login")
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
            get("/auth/login")
                .with(httpBasic(userId.toString(), "wrong-password"))
        )
            .andExpect(status().isUnauthorized)
    }
}
