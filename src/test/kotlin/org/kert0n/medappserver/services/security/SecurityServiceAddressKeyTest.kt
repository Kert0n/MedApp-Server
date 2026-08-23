package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import org.springframework.test.context.ActiveProfiles

/**
 * ARCHITECTURE.md и README обещают, что адрес клиента лежит в кэше только в виде хеша. Здесь это
 * обещание закреплено — именно как хеш, а не как «что-нибудь непохожее на адрес».
 *
 * Ожидание считается тем же способом, что и ключ, и это намеренный сторож: смена выведения
 * обязана быть отдельным решением, а не побочным следствием правки в сервисе.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityServiceAddressKeyTest {

    @Autowired
    private lateinit var securityService: SecurityService

    @Autowired
    private lateinit var successfulRegistrationsCache: Cache<String, Int>

    @Test
    fun `client address is never used as a cache key`() {
        val address = "203.0.113.77"

        securityService.registerIncrease(address)

        val keys = successfulRegistrationsCache.asDeferredMap().keys
        assertTrue(keys.isNotEmpty(), "the registration must have been recorded")
        assertFalse(
            keys.contains(address),
            "raw client address leaked into the cache as a key: $keys"
        )
        assertFalse(
            keys.any { it.contains("203.0.113") },
            "cache key still contains a recognisable fragment of the address: $keys"
        )
        assertTrue(
            keys.contains(Base64.encode(MessageDigest.getInstance("SHA-256").digest(address.toByteArray()))),
            "ключ выведен не SHA-256 от адреса: $keys"
        )
    }

    @Test
    fun `counting stays consistent for the same address`() {
        val address = "198.51.100.42"

        val before = successfulRegistrationsCache.asDeferredMap().keys.toSet()
        securityService.registerIncrease(address)
        securityService.registerIncrease(address)
        val added = successfulRegistrationsCache.asDeferredMap().keys - before

        // Оба инкремента обязаны попасть в один и тот же производный ключ: неустойчивое
        // выведение давало бы новый ключ на каждый вызов и никакого ограничения.
        assertEquals(1, added.size, "two increments for one address produced keys: $added")
    }
}
