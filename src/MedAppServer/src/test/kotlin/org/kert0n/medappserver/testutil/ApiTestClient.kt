package org.kert0n.medappserver.testutil

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Пути API в одном месте.
 *
 * Ресурсы ещё переезжают на `/v1`, и без такого списка каждый переезд правил бы по десятку
 * тестовых файлов. Здесь пока только аутентификация — остальные маршруты приедут вместе с
 * ресурсным API.
 */
object ApiRoutes {
    const val REGISTER = "/v1/auth/register"
    const val TOKEN = "/v1/auth/token"
}

/** Обёртка над MockMvc для публичных операций аутентификации. */
class ApiTestClient(private val mockMvc: MockMvc) {

    fun register(secret: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.REGISTER).header(REGISTRATION_TOKEN_HEADER, secret)
    )

    /** Запрос токена с уже готовым заголовком Authorization — в том числе намеренно кривым. */
    fun tokenWithAuthorization(header: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.TOKEN).header("Authorization", header)
    )

    fun tokenWithoutCredentials(): ResultActions = mockMvc.perform(post(ApiRoutes.TOKEN))

    private companion object {
        const val REGISTRATION_TOKEN_HEADER = "X-Registration-Token"
    }
}
