package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.db.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
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
    private val successfulRegistrationsCache: Cache<String, Int>
) {

    private val secureRandom = SecureRandom()


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
     * Cache key for a client address, so no address is used as a key verbatim.
     *
     * Plain SHA-256 on purpose. A keyed digest would be more machinery for nothing here:
     * entries expire within minutes, the cache is in-memory and dies with the process, and
     * anyone able to read that heap can see the live connections anyway.
     */
    private fun addressCacheKey(clientAddress: String): String = hashToken(clientAddress)

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
