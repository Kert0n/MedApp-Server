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
 * The registration limit keys on the client address, so behind a reverse proxy it only means
 * anything when X-Forwarded-For is honoured — otherwise every client shares the proxy's address
 * and one global counter.
 *
 * Real HTTP on purpose: with forward-headers-strategy=native the rewriting is Tomcat's
 * RemoteIpValve, which MockMvc bypasses, so a MockMvc test would pass over a broken production.
 *
 * The test profile sets BanNumber=1 and `validateRequest` compares with `<=`, so two
 * registrations per address succeed and the third is rejected — accepted off-by-one, see
 * SecurityService.validateRequest.
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

        // The decisive assertion: a different forwarded address still has its own
        // quota. If the header were ignored, this would share the exhausted counter.
        assertEquals(200, register(second), "$second must have an independent quota")
    }
}
