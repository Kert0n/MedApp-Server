package org.kert0n.medappserver.integration

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Вторая половина доверия к X-Forwarded-For, без которой первая опасна.
 *
 * Лимит регистраций считается по адресу клиента. Если заголовок принимать от кого угодно,
 * лимит обходится тривиально: достаточно менять его значение в каждом запросе. Защита
 * держится на том, что Tomcat RemoteIpValve подставляет адрес из заголовка **только** для
 * peer'ов из доверенных диапазонов, а снаружи приходит настоящий адрес.
 *
 * Здесь диапазон доверенных сужен так, что localhost в него не входит, то есть тестовый
 * клиент выступает недоверенным источником. Идёт через настоящий HTTP: заголовок разбирает
 * Tomcat, MockMvc его не задействует.
 *
 * Профиль test задаёт registration.timeout.BanNumber=1, а validateRequest сравнивает через
 * `<=`, поэтому с одного адреса проходят две регистрации, третья отклоняется.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.forward-headers-strategy=native",
        // Доверенными объявлены только адреса 10.0.0.0/8, поэтому клиент теста (петля)
        // доверенным узлом не является. Проверено обратным прогоном: без этой строки
        // петля попадает в список по умолчанию, заголовку верят и тест падает.
        "server.tomcat.remoteip.internal-proxies=10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
    ]
)
@ActiveProfiles("test")
class UntrustedForwardedAddressTest {

    @Value($$"${local.server.port}")
    private var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun register(forwardedFor: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/auth/register"))
            .header("X-Registration-Token", "test-secret")
            .header("X-Forwarded-For", forwardedFor)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `подменённый адрес от недоверенного узла не даёт новую квоту`() {
        assertEquals(200, register("203.0.113.10"))
        assertEquals(200, register("198.51.100.20"))

        // Решающая проверка: адрес в заголовке снова другой. Если бы ему верили, квота была
        // бы своя и запрос прошёл бы — то есть лимит обходился бы сменой заголовка.
        assertEquals(
            429, register("192.0.2.30"),
            "заголовок от недоверенного узла обязан игнорироваться, иначе лимит обходится"
        )

        // Решающая проверка: адрес в заголовке снова другой. Если бы ему верили, квота была
        // бы своя и запрос прошёл бы — то есть лимит обходился бы сменой заголовка.

    }
}
