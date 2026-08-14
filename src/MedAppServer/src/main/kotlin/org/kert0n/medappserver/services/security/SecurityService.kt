package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.db.model.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
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


fun hashToken(token: String): String =
    Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

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

    fun generateKey(size: Int): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(ByteArray(size).also { secureRandom.nextBytes(it) })


    fun check(raw: String, hashedPassword: String): Boolean =
        passwordEncoder.matches(raw, hashedPassword)

    fun hashPassword(rawPassword: String): String =
        passwordEncoder.encode(rawPassword)!!


    fun secretsMatch(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(
        hashToken(candidate).toByteArray(StandardCharsets.UTF_8),
        hashToken(expected).toByteArray(StandardCharsets.UTF_8)
    )


    /**
     * Токен по аутентификации запроса.
     *
     * Каст принципала живёт здесь, а не в контроллере: тип принципала задаёт
     * SecurityConfiguration вместе с UserDetailsService, и знать о нём — работа этого
     * пакета. Пока каст стоял в AuthController, контроллер импортировал сущность БД ради
     * одной строки.
     */
    fun generateToken(authentication: Authentication): String =
        generateToken(authentication.principal as User)

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

    private fun hashIP(clientAddress: String): String = hashToken(clientAddress)

    fun isRegistrationAllowed(clientAdress: String): Boolean =
        (successfulRegistrationsCache.getOrNull(hashIP(clientAdress)) ?: 0) < registrationNumber

    fun isLoginAllowed(clientAddress: String): Boolean =
        (loginAttemptsCache.getOrNull(hashIP(clientAddress)) ?: 0) < maxLoginAttempts

    fun recordLoginAttempt(clientAddress: String) {
        val key = hashIP(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

    fun recordRegisterAttempt(clientAddress: String) {
        val key = hashIP(clientAddress)
        successfulRegistrationsCache[key] = (successfulRegistrationsCache.getOrNull(key) ?: 0) + 1

    }
}