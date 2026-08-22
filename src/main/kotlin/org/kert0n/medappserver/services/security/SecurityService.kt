package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.encoding.Base64
import org.kert0n.medappserver.domain.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service

@Service
class SecurityService(
    private val passwordEncoder: PasswordEncoder,
    private val encoder: JwtEncoder,
    private val decoder: JwtDecoder,
    @Value($$"${authentication.termInMinutes}") private val authenticationTerm: Long,
    @Value($$"${registration.timeout.BanNumber}") private val registrationNumber: Long,
    @Value($$"${authentication.throttle.maxAttempts:20}") private val maxLoginAttempts: Int,
    // Both caches are Cache<String, Int>: qualified so resolution does not rely on names.
    @Qualifier("successfulRegistrationsCache") private val successfulRegistrationsCache: Cache<String, Int>,
    @Qualifier("loginAttemptsCache") private val loginAttemptsCache: Cache<String, Int>
) {

    private val secureRandom = SecureRandom()

    /**
     * Ключ печатается в URL приглашения и уезжает в заголовок Basic, поэтому URL-safe и без
     * набивки: `+`, `/` и `=` пришлось бы экранировать, и клиенты сделали бы это по-разному.
     */
    fun generateKey(size: Int) = URL_SAFE_KEYS.encode(ByteArray(size).also { secureRandom.nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!
    fun hashToken(token: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /**
     * Compares two shared secrets without leaking how much of a candidate was correct.
     *
     * `==` stops at the first differing character, so timing follows the matching prefix. Both
     * sides are hashed because [MessageDigest.isEqual] returns early on a length mismatch —
     * hashing equalises the lengths, hiding the size of the expected secret too.
     */
    fun secretsMatch(candidate: String, expected: String): Boolean = MessageDigest.isEqual(
        hashToken(candidate).toByteArray(StandardCharsets.UTF_8),
        hashToken(expected).toByteArray(StandardCharsets.UTF_8)
    )


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
     * Cache key for a client address, so no address is used as a key verbatim.
     *
     * Plain SHA-256 on purpose: entries expire within minutes, the cache dies with the process,
     * and anyone able to read that heap sees the live connections anyway.
     */
    private fun addressCacheKey(clientAddress: String): String = hashToken(clientAddress)

    /**
     * Tracks successful registrations per client address to throttle automated signups.
     *
     * Two imprecisions are deliberate — this deters casual bots and is not a security boundary:
     * the check precedes the increment, so concurrent requests slip past together, and `<=`
     * allows one more than [registrationNumber]. Exactness would need an atomic counter with a
     * release on failure: machinery for no real gain here. Do not "fix" without a reason.
     */
    fun validateRequest(ip: String): Boolean =
        (successfulRegistrationsCache.getOrNull(addressCacheKey(ip)) ?: 0) <= registrationNumber

    /**
     * Whether another token request from this address may proceed to authentication.
     *
     * Strict comparison, unlike [validateRequest]: this guards a real cost — a bcrypt
     * verification per request — not just bots.
     */
    fun isLoginAllowed(clientAddress: String): Boolean =
        (loginAttemptsCache.getOrNull(addressCacheKey(clientAddress)) ?: 0) < maxLoginAttempts

    /**
     * Counts a token request — every attempt, not just failures.
     *
     * A legitimate client asks roughly once per token lifetime, far below the limit, and
     * counting all attempts also covers an attacker holding valid credentials.
     */
    fun recordLoginAttempt(clientAddress: String) {
        val key = addressCacheKey(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

    private companion object {
        val URL_SAFE_KEYS: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }

    /** Counts one more successful registration from this address. */
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
