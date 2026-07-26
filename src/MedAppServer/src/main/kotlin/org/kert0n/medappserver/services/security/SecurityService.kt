package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.db.model.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

@Service
class SecurityService(
    private val passwordEncoder: PasswordEncoder,
    private val encoder: JwtEncoder,
    private val decoder: JwtDecoder,
    @Value($$"${authentication.termInMinutes}") private val authenticationTerm: Long,
    @Value($$"${registration.timeout.BanNumber}") private val registrationNumber: Long,
    @Value($$"${authentication.throttle.maxAttempts:20}") private val maxLoginAttempts: Int,
    // Both caches are Cache<String, Int>; qualify them so resolution does not rely on
    // parameter-name matching.
    @Qualifier("successfulRegistrationsCache") private val successfulRegistrationsCache: Cache<String, Int>,
    @Qualifier("loginAttemptsCache") private val loginAttemptsCache: Cache<String, Int>
) {

    private val secureRandom = SecureRandom()

    /**
     * Per-process key used to derive cache keys from client addresses.
     *
     * A plain digest would be security theatre here: the whole IPv4 space can be
     * enumerated in seconds, so SHA-256(address) is trivially reversible. This key never
     * leaves the process and is never persisted, so a heap dump of the cache cannot be
     * mapped back to addresses; a restart makes previous keys meaningless, which is
     * harmless because the cache is in-memory and dies with the process anyway.
     */
    private val addressKey = ByteArray(32).also { secureRandom.nextBytes(it) }

    fun generateKey(size: Int) = Base64.encode(ByteArray(size).also { secureRandom.nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!
    fun hashToken(token: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))


    fun generateToken(user: User, termInMinutes: Long = authenticationTerm): String {
        val now = Instant.now()
        return encoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().run {
                    issuedAt(now)
                    expiresAt(now.plus(termInMinutes, ChronoUnit.MINUTES))
                    subject(user.id.toString())
                    build()
                }
            )
        ).tokenValue
    }

    /**
     * Derives the cache key for a client address. Addresses are never used as keys
     * directly, so the cache holds no client address in a recoverable form.
     */
    private fun addressCacheKey(clientAddress: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(addressKey, "HmacSHA256"))
        return Base64.encode(mac.doFinal(clientAddress.toByteArray(StandardCharsets.UTF_8)))
    }

    /**
     * Tracks successful registrations per client address to throttle automated signups.
     *
     * Two imprecisions are accepted deliberately, because this only has to deter casual
     * bots and is not a security boundary:
     *  - the check happens before the increment, so concurrent requests can slip past the
     *    limit together;
     *  - the comparison is `<=`, so one registration more than [registrationNumber] is
     *    allowed.
     * Making this exact would need an atomic counter with a release on failed
     * registration. That is not a coroutine or asynchrony question — an AtomicInteger
     * would do — but it is extra machinery for no real gain here, so it is left out on
     * purpose. Do not "fix" this without a reason.
     */
    fun validateRequest(ip: String): Boolean =
        (successfulRegistrationsCache.getOrNull(addressCacheKey(ip)) ?: 0) <= registrationNumber

    /**
     * Whether another token request from this address may proceed to authentication.
     *
     * Unlike [validateRequest] this uses a strict comparison: it guards a real cost
     * (a bcrypt verification per request) rather than merely deterring bots.
     */
    fun isLoginAllowed(clientAddress: String): Boolean =
        (loginAttemptsCache.getOrNull(addressCacheKey(clientAddress)) ?: 0) < maxLoginAttempts

    /**
     * Counts a token request. Every attempt is counted, not just failures: a legitimate
     * client asks for a token roughly once per token lifetime, so the limit is far above
     * normal use, and counting all attempts also covers an attacker holding valid
     * credentials.
     */
    fun recordLoginAttempt(clientAddress: String) {
        val key = addressCacheKey(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

    // Creates or increases successful registration attempt from a client address
    fun registerIncrease(ip: String) {
        val key = addressCacheKey(ip)
        val current = successfulRegistrationsCache.getOrNull(key)
        if (current == null) {
            successfulRegistrationsCache.put(key, 1)
        } else {
            successfulRegistrationsCache[key] = current + 1
        }
    }
}
