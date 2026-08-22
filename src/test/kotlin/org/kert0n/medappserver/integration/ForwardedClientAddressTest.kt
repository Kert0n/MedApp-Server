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
 * Ограничение регистраций ключуется адресом клиента, поэтому за обратным прокси оно что-то
 * значит только тогда, когда учитывается X-Forwarded-For: иначе у всех клиентов один адрес
 * прокси и один общий счётчик.
 *
 * Настоящий HTTP, а не MockMvc: при `forward-headers-strategy=native` подмену делает
 * `RemoteIpValve` самого Tomcat, мимо которого MockMvc проходит, — тест на MockMvc был бы
 * зелёным над сломанным продом.
 *
 * В тестовом профиле `BanNumber=1`, а `validateRequest` сравнивает через `<=`: две регистрации
 * с адреса проходят, третья отвергается. Смещение на единицу принято — см.
 * `SecurityService.validateRequest`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.forward-headers-strategy=native"]
)
@ActiveProfiles("test")
class ForwardedClientAddressTest {

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
    fun `registration limit is counted per forwarded client address`() {
        val first = "203.0.113.10"
        val second = "198.51.100.7"

        assertEquals(200, register(first), "first registration from $first")
        assertEquals(200, register(first), "second registration from $first")
        assertEquals(429, register(first), "third registration from $first must exhaust that address' quota")

        // Решающая проверка: у другого проброшенного адреса своя квота. Игнорируйся
        // заголовок — этот запрос делил бы уже исчерпанный счётчик.
        assertEquals(200, register(second), "$second must have an independent quota")
    }
}
