package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * ARCHITECTURE.md and the README state that client addresses are only ever held in the cache in
 * hashed form. This pins that claim down.
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
    }

    @Test
    fun `counting stays consistent for the same address`() {
        val address = "198.51.100.42"

        val before = successfulRegistrationsCache.asDeferredMap().keys.toSet()
        securityService.registerIncrease(address)
        securityService.registerIncrease(address)
        val added = successfulRegistrationsCache.asDeferredMap().keys - before

        // Both increments must land on the same derived key: unstable derivation would mean a
        // fresh key per call and no throttling at all.
        assertEquals(1, added.size, "two increments for one address produced keys: $added")
    }
}
