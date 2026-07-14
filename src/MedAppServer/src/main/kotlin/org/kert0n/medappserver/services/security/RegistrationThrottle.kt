package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.config.RegistrationProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

@Service
class RegistrationThrottle(
    private val properties: RegistrationProperties,
    private val counters: Cache<String, AtomicInteger>
) {
    private val processKey = ByteArray(32).also(SecureRandom()::nextBytes)

    fun isValidRegistrationToken(candidate: String): Boolean = MessageDigest.isEqual(
        candidate.toByteArray(StandardCharsets.UTF_8),
        properties.secret.toByteArray(StandardCharsets.UTF_8)
    )

    /**
     * Atomically reserves one successful registration slot. Call [RegistrationPermit.commit]
     * only after the user has been persisted; closing an uncommitted permit releases the slot.
     */
    suspend fun tryAcquire(clientAddress: String): RegistrationPermit? {
        val counter = counters.get(toTransientKey(clientAddress)) { AtomicInteger(0) }
        if (counter.incrementAndGet() > properties.throttle.maxSuccessfulRegistrations) {
            counter.decrementAndGet()
            return null
        }
        return RegistrationPermit(counter)
    }

    private fun toTransientKey(clientAddress: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(processKey, "HmacSHA256"))
        return Base64.encode(mac.doFinal(clientAddress.toByteArray(StandardCharsets.UTF_8)))
    }
}

class RegistrationPermit internal constructor(
    private val counter: AtomicInteger
) : AutoCloseable {
    private val committed = AtomicBoolean(false)

    fun commit() {
        committed.set(true)
    }

    override fun close() {
        if (committed.compareAndSet(false, true)) {
            counter.decrementAndGet()
        }
    }
}
