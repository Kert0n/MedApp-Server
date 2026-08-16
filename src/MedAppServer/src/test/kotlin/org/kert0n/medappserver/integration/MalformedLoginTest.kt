package org.kert0n.medappserver.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.ApiTestClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.Base64

/**
 * Кривой заголовок Authorization — это неудачная аутентификация, а не поломка сервера.
 *
 * Такие запросы приходят постоянно: сканеры, оборванные клиенты, чужие прокси. Ответ 500
 * на них означал бы, что любой желающий может писать в логи стектрейсы, а мониторинг
 * ошибок считал бы это отказом сервиса.
 */
@SpringBootTest
@ActiveProfiles("test")
class MalformedLoginTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var api: ApiTestClient

    @BeforeEach
    fun setup() {
        val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        api = ApiTestClient(mockMvc)
    }

    private fun basic(credentials: String): String =
        "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())

    @Test
    fun `заголовок без base64 отвергается как неудачная аутентификация`() {
        api.tokenWithAuthorization("Basic ��не-base64��")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `логин без двоеточия отвергается`() {
        // В Basic логин и пароль разделяет двоеточие; без него пары просто нет.
        api.tokenWithAuthorization(basic("логин-без-разделителя"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `логин не в форме идентификатора отвергается`() {
        // loadUserByUsername парсит логин как UUID: неразобранная строка не должна
        // превращаться в 500.
        api.tokenWithAuthorization(basic("не-uuid:пароль"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `неизвестная схема авторизации отвергается`() {
        api.tokenWithAuthorization("Bearer не-тот-механизм")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `отсутствие учётных данных отвечает problem+json`() {
        api.tokenWithoutCredentials()
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
    }
}
