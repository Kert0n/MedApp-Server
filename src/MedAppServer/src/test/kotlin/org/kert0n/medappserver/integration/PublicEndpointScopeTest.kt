package org.kert0n.medappserver.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.services.models.UserService
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * Pins down which endpoints are reachable without a token, and that HTTP Basic is
 * confined to token issuance.
 *
 * Both used to be too wide: the whole actuator and auth path prefixes were open, and
 * Basic applied to every endpoint — so the long-lived registration key could be replayed
 * on any request and the 10 minute token lifetime was decorative.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicEndpointScopeTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var userService: UserService

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

    @Test
    fun `swagger entry point is public`() {
        // Обе строки, а не одна: /swagger-ui/** — Ant-шаблон по содержимому каталога, и
        // соседний /swagger-ui.html под него не подходит. Springdoc считает точкой входа
        // именно .html, поэтому Swagger отвечал 401, хотя index.html открывался.
        // 3xx, а не 200: springdoc перенаправляет точку входа на index.html. Важно, что это
        // перенаправление, а не 401, — до правки список публичных путей отдавал здесь отказ.
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection)
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }

    @Test
    fun `other actuator endpoints require authentication`() {
        // Not exposed over HTTP by default either, so 401 rather than 404 is what we
        // want: the guard must not depend on the exposure setting.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `business endpoints reject HTTP Basic credentials`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)

        // Valid credentials, but Basic is not an accepted scheme outside token issuance.
        mockMvc.perform(
            get("/v1/user").with(httpBasic(userId.toString(), "password"))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token issuance still accepts HTTP Basic`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(userService.loadUserByUsername(userId.toString())).thenReturn(user)

        mockMvc.perform(
            get("/v1/auth/login").with(httpBasic(userId.toString(), "password"))
        ).andExpect(status().isOk)
    }

    @Test
    fun `business endpoints reject anonymous requests`() {
        mockMvc.perform(get("/v1/user")).andExpect(status().isUnauthorized)
    }
}
