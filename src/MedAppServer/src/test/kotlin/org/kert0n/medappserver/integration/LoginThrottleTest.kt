package org.kert0n.medappserver.integration

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.AuthenticatedUserService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Every token request costs a bcrypt verification, so an unauthenticated caller could
 * burn CPU for free. The limit has to bite before authentication runs, which is why it
 * lives in a filter and not in the controller.
 */
@SpringBootTest(properties = ["authentication.throttle.maxAttempts=3"])
@ActiveProfiles("test")
class LoginThrottleTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    @Qualifier("loginAttemptsCache")
    private lateinit var loginAttemptsCache: Cache<String, Int>

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
        // The cache is a context-wide singleton and MockMvc always reports the same address,
        // so without this the next test starts already throttled.
        loginAttemptsCache.invalidateAll()
    }

    @Test
    fun `token requests are capped`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        repeat(3) {
            mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
                .andExpect(status().isOk)
        }

        // Quota exhausted: 429 rather than 401, i.e. throttling and not an authentication
        // failure.
        mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `throttled request never reaches credential verification`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        // No credentials at all: the filter counts before Basic runs, so these burn quota too.
        repeat(3) {
            mockMvc.perform(post(ApiRoutes.TOKEN)).andExpect(status().isUnauthorized)
        }

        mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isTooManyRequests)

        // The decisive assertion: the user lookup — and the bcrypt after it — never ran.
        verify(authenticatedUserService, never()).loadUserByUsername(userId.toString())
    }
}
