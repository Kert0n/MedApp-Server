package org.kert0n.medappserver.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Логин, который не разбирается как UUID, — это отказ аутентификации, а не сбой сервера.
 *
 * `UUID.fromString("abc")` бросает `IllegalArgumentException`, Spring Security заворачивает
 * его в `InternalAuthenticationServiceException`. Статус наружу был верным и до правки —
 * 401, потому что это всё-таки `AuthenticationException`, — но каждый такой запрос печатал
 * в лог полный стектрейс как внутреннюю ошибку. Чинится именно это: неаутентифицированный
 * клиент не должен уметь писать стектрейсы в чужой лог.
 *
 * Отсюда деление тестов. HTTP-проверки ниже закрепляют контракт (401 при любом мусоре) и
 * прошли бы и на старом коде — они пины, а не доказательство. Отличает состояния только
 * последний тест: он смотрит на тип исключения, то есть на причину, а не на её следствие.
 * `UserService` поэтому настоящий, а не мок.
 */
@SpringBootTest
@ActiveProfiles("test")
class MalformedLoginTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var userService: UserService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `не-UUID логин в Basic даёт 401, а не 500`() {
        mockMvc.perform(get("/v1/auth/login").with(httpBasic("abc", "не важно")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `пустой логин тоже 401`() {
        mockMvc.perform(get("/v1/auth/login").with(httpBasic("", "не важно")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `несуществующий, но синтаксически верный UUID тоже 401`() {
        // Отдельный кейс: раньше эти две ситуации шли разными путями — одна через
        // исключение разбора, другая через отсутствие записи. Наружу должны выглядеть
        // одинаково, иначе по коду ответа различаются «нет такого» и «формат не тот».
        mockMvc.perform(get("/v1/auth/login").with(httpBasic(UUID.randomUUID().toString(), "не важно")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `loadUserByUsername на мусоре бросает UsernameNotFoundException`() {
        // Тип исключения и есть причина статуса: любое другое Spring Security трактует
        // как сбой сервера. Проверяем его прямо, чтобы поломка не пряталась за HTTP-кодом.
        assertFailsWith<UsernameNotFoundException> { userService.loadUserByUsername("abc") }
    }
}
