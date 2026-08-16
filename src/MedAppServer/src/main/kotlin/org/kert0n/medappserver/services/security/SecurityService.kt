package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
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

    fun generateKey(size: Int): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(size).also { secureRandom.nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!
    fun hashToken(token: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /** Compares fixed-length secret digests in constant time. */
    fun secretsMatch(candidate: String, expected: String): Boolean = MessageDigest.isEqual(
        hashToken(candidate).toByteArray(StandardCharsets.UTF_8),
        hashToken(expected).toByteArray(StandardCharsets.UTF_8)
    )


    fun generateToken(subject: String, termInMinutes: Long = authenticationTerm): String {
        val now = Instant.now()
        return encoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().run {
                    issuedAt(now)
                    expiresAt(now.plus(termInMinutes, ChronoUnit.MINUTES))
                    subject(subject)
                    build()
                }
            )
        ).tokenValue
    }

    /** Client addresses are represented in ephemeral caches only by their SHA-256 digest. */
    private fun addressCacheKey(clientAddress: String): String = hashToken(clientAddress)

    /** Registration throttling is a best-effort abuse control, not an authorization boundary. */
    fun validateRequest(ip: String): Boolean =
        (successfulRegistrationsCache.getOrNull(addressCacheKey(ip)) ?: 0) <= registrationNumber

    /** Rejects token attempts before their bcrypt verification exceeds the configured limit. */
    fun isLoginAllowed(clientAddress: String): Boolean =
        (loginAttemptsCache.getOrNull(addressCacheKey(clientAddress)) ?: 0) < maxLoginAttempts

    /** Counts every token attempt, including requests with valid credentials. */
    fun recordLoginAttempt(clientAddress: String) {
        val key = addressCacheKey(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

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
