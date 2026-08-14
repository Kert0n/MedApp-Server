package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * Правило: не чаще трёх регистраций с одного адреса за пятнадцать минут.
 *
 * Отдельный набор, а не проверка внутри ForwardedClientAddressTest: там квота равна единице
 * и тест про другое — что счётчик ведётся по пересланному адресу. Здесь закрепляется само
 * число, поэтому лимит задаётся свойством, а не берётся из общего тестового профиля.
 *
 * Через настоящий HTTP, а не MockMvc: лимит смотрит на адрес клиента, а MockMvc идёт мимо
 * контейнера сервлетов, где этот адрес и определяется.
 *
 * Окно (registration.timeout.InSeconds) здесь не проверяется: ждать его истечения в тесте
 * нечем, кроме сна, а сон в наборе — это минуты простоя на каждом прогоне. Проверяется то,
 * что от него не зависит, — счёт.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.forward-headers-strategy=native",
        "registration.timeout.BanNumber=3",
        "registration.timeout.InSeconds=900"
    ]
)
@ActiveProfiles("test")
class RegistrationThrottleTest {

    @Value($$"${local.server.port}")
    private var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun register(address: String, bearer: String? = null): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/auth/register"))
            .header("X-Registration-Token", "test-secret")
            .header("X-Forwarded-For", address)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `с одного адреса проходит ровно три регистрации`() {
        val address = "203.0.113.21"

        repeat(3) { attempt ->
            assertEquals(200, register(address), "регистрация ${attempt + 1} из трёх обязана пройти")
        }
        // Именно четвёртая, а не пятая. Раньше сравнение в лимите было нестрогим, и при
        // объявленных трёх фактически разрешались четыре.
        assertEquals(429, register(address), "четвёртая регистрация с того же адреса обязана быть отклонена")
    }

    @Test
    fun `квота не зависит от того, есть ли у вызывающего токен`() {
        val address = "203.0.113.22"

        // Регистрация — публичный эндпоинт, и пройти на него может как аноним, так и клиент
        // с действующим токеном. Освобождения для второго нет и быть не должно: иначе
        // достаточно один раз зарегистрироваться, чтобы снять с себя ограничение.
        val token = issueToken(address)
        assertEquals(200, register(address, bearer = token), "вторая регистрация в пределах квоты")
        assertEquals(200, register(address, bearer = token), "третья регистрация в пределах квоты")
        assertEquals(
            429, register(address, bearer = token),
            "квота исчерпана — токен не даёт обойти лимит"
        )
    }

    /** Регистрируется (первая из квоты) и обменивает выданные ключи на токен. */
    private fun issueToken(address: String): String {
        val body = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/auth/register"))
            .header("X-Registration-Token", "test-secret")
            .header("X-Forwarded-For", address)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
            .let { client.send(it, HttpResponse.BodyHandlers.ofString()) }
        assertEquals(200, body.statusCode(), "подготовка: первая регистрация обязана пройти")

        val login = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(body.body())!!.groupValues[1]
        val key = Regex("\"key\"\\s*:\\s*\"([^\"]+)\"").find(body.body())!!.groupValues[1]
        val basic = java.util.Base64.getEncoder().encodeToString("$login:$key".toByteArray())

        val login2 = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/auth/login"))
            .header("Authorization", "Basic $basic")
            .GET()
            .build()
            .let { client.send(it, HttpResponse.BodyHandlers.ofString()) }
        assertEquals(200, login2.statusCode(), "подготовка: выдача токена обязана пройти")
        return login2.body()
    }
}
