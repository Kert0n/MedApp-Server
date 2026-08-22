package org.kert0n.medappserver.integration

import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.UserService
import org.kert0n.medappserver.services.security.AuthenticatedUserService
import org.kert0n.medappserver.testutil.ApiRoutes
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Pins down which endpoints are reachable without a token, and that HTTP Basic is confined to
 * token issuance — otherwise the long-lived registration key is replayable on any request and
 * the token lifetime is decorative.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicEndpointScopeTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var authenticatedUserService: AuthenticatedUserService

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `health is public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    /**
     * Документация открывается без токена — и по тому адресу, который открыт в конфигурации
     * безопасности: разрешённый, но ни на что не отображённый путь отвечал бы 404 с
     * Whitelabel-страницей.
     */
    @Test
    fun `документация доступна без токена`() {
        mockMvc.perform(get("/swagger")).andExpect(status().is3xxRedirection)
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }

    @Test
    fun `other actuator endpoints require authentication`() {
        // Not exposed over HTTP by default either, so 401 rather than 404: the guard must not
        // depend on the exposure setting.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `business endpoints reject HTTP Basic credentials`() {
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        // Valid credentials, but Basic is not an accepted scheme outside token issuance.
        mockMvc.perform(
            get("/user").with(httpBasic(userId.toString(), "password"))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token issuance still accepts HTTP Basic`() {
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        mockMvc.perform(
            post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password"))
        ).andExpect(status().isOk)
    }

    @Test
    fun `business endpoints reject anonymous requests`() {
        mockMvc.perform(get("/user")).andExpect(status().isUnauthorized)
    }
}
