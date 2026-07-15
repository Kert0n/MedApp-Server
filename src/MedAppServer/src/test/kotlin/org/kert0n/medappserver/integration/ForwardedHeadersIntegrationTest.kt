package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.forward-headers-strategy=NATIVE",
        "server.tomcat.remoteip.internal-proxies=127.0.0.0/8,::1/128",
        "registration.throttle.max-successful-registrations=1",
        "spring.datasource.url=jdbc:h2:mem:forwarded-trusted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    ]
)
@ActiveProfiles("test")
class ForwardedHeadersIntegrationTest {
    @Value($$"${local.server.port}")
    private var port: Int = 0

    @Test
    fun `trusted proxy addresses produce independent registration windows`() {
        assertEquals(HttpStatus.CREATED, registerFrom("203.0.113.10"))
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, registerFrom("203.0.113.10"))
        assertEquals(HttpStatus.CREATED, registerFrom("198.51.100.20"))
    }

    private fun registerFrom(address: String): HttpStatus {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/auth/register"))
            .header("X-Registration-Token", "test-secret")
            .header("X-Forwarded-For", address)
            .header("X-Forwarded-Proto", "https")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        return HttpStatus.valueOf(response.statusCode())
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.forward-headers-strategy=NATIVE",
        "server.tomcat.remoteip.internal-proxies=10.0.0.0/8",
        "registration.throttle.max-successful-registrations=1",
        "spring.datasource.url=jdbc:h2:mem:forwarded-untrusted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
    ]
)
@ActiveProfiles("test")
class UntrustedForwardedHeadersIntegrationTest {
    @Value($$"${local.server.port}")
    private var port: Int = 0

    @Test
    fun `untrusted caller cannot bypass throttle by spoofing forwarded address`() {
        assertEquals(HttpStatus.CREATED, registerFrom("203.0.113.10"))
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, registerFrom("198.51.100.20"))
    }

    private fun registerFrom(address: String): HttpStatus {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/auth/register"))
            .header("X-Registration-Token", "test-secret")
            .header("X-Forwarded-For", address)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        return HttpStatus.valueOf(response.statusCode())
    }
}
