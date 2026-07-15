package org.kert0n.medappserver.services.security

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.Cache
import com.sksamuel.aedile.core.asCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationThrottleTest {
    private fun fixture(max: Int = 3): Pair<RegistrationThrottle, Cache<String, AtomicInteger>> {
        val cache: Cache<String, AtomicInteger> = Caffeine.newBuilder().asCache()
        return RegistrationThrottle("test-secret", max, cache) to cache
    }

    @Test
    fun `token comparison accepts only configured token`() {
        val (throttle) = fixture()

        assertTrue(throttle.isValidRegistrationToken("test-secret"))
        assertFalse(throttle.isValidRegistrationToken("wrong-secret"))
    }

    @Test
    fun `configuration rejects blank secret and non-positive limit`() {
        val cache: Cache<String, AtomicInteger> = Caffeine.newBuilder().asCache()

        assertFailsWith<IllegalArgumentException> { RegistrationThrottle(" ", 1, cache) }
        assertFailsWith<IllegalArgumentException> { RegistrationThrottle("test-secret", 0, cache) }
    }

    @Test
    fun `concurrent requests acquire exactly configured number of permits`() = runBlocking {
        val (throttle) = fixture(max = 3)

        val permits = (1..50).map {
            async { throttle.tryAcquire("203.0.113.10") }
        }.awaitAll()

        assertEquals(3, permits.count { it != null })
        permits.filterNotNull().forEach { it.commit() }
    }

    @Test
    fun `uncommitted permit is released`() = runBlocking {
        val (throttle) = fixture(max = 1)

        throttle.tryAcquire("203.0.113.10").use { assertNotNull(it) }

        assertNotNull(throttle.tryAcquire("203.0.113.10"))
    }

    @Test
    fun `committed permit consumes the slot`() = runBlocking {
        val (throttle) = fixture(max = 1)

        throttle.tryAcquire("203.0.113.10")!!.commit()

        assertNull(throttle.tryAcquire("203.0.113.10"))
    }

    @Test
    fun `cache stores a transient hmac instead of raw address`() = runBlocking {
        val (throttle, cache) = fixture()
        val address = "203.0.113.10"

        throttle.tryAcquire(address)!!.commit()

        val key = cache.asDeferredMap().keys.single()
        assertNotEquals(address, key)
        assertFalse(key.contains(address))
    }
}
