package org.kert0n.medappserver.integration

import com.sksamuel.aedile.core.Cache
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.UserService
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
 * Каждый запрос токена стоит одной проверки bcrypt, поэтому неаутентифицированный вызывающий
 * жёг бы процессор даром. Ограничение обязано сработать до аутентификации — оттого оно живёт в
 * фильтре, а не в контроллере.
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
        // Кэш — синглтон на весь контекст, а MockMvc всегда сообщает один и тот же адрес:
        // без сброса следующий тест начинается уже под ограничением.
        loginAttemptsCache.invalidateAll()
    }

    @Test
    fun `token requests are capped`() {
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        repeat(3) {
            mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
                .andExpect(status().isOk)
        }

        // Квота исчерпана: 429, а не 401, — то есть ограничение, а не отказ аутентификации.
        mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `throttled request never reaches credential verification`() {
        val userId = Uuid.random()
        val user = User(id = userId, hashedKey = "{noop}password")
        whenever(authenticatedUserService.loadUserByUsername(userId.toString())).thenReturn(user)

        // Учётных данных нет вовсе: фильтр считает до Basic, так что и такие запросы тратят квоту.
        repeat(3) {
            mockMvc.perform(post(ApiRoutes.TOKEN)).andExpect(status().isUnauthorized)
        }

        mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
            .andExpect(status().isTooManyRequests)

        // Решающая проверка: поиска пользователя — и bcrypt за ним — не было вовсе.
        verify(authenticatedUserService, never()).loadUserByUsername(userId.toString())
    }
}
