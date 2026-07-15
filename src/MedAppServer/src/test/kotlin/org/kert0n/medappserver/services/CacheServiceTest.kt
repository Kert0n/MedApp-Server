package org.kert0n.medappserver.services

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith

class CacheServiceTest {
    @Test
    fun `configuration rejects non-positive durations`() {
        assertFailsWith<IllegalArgumentException> {
            CacheService(0, Duration.ofMinutes(5))
        }
        assertFailsWith<IllegalArgumentException> {
            CacheService(10, Duration.ZERO)
        }
    }
}
