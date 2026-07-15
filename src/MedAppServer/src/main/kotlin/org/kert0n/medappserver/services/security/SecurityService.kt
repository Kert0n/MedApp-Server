package org.kert0n.medappserver.services.security

import org.kert0n.medappserver.db.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.Duration
import kotlin.io.encoding.Base64

@Service
class SecurityService(
    private val passwordEncoder: PasswordEncoder,
    private val encoder: JwtEncoder,
    @Value($$"${authentication.term:10m}") private val authenticationTerm: Duration,
    @Value($$"${authentication.issuer:medapp-server}") private val authenticationIssuer: String,
    @Value($$"${authentication.audience:medapp-api}") private val authenticationAudience: String,
) {
    init {
        require(!authenticationTerm.isZero && !authenticationTerm.isNegative) {
            "authentication.term must be positive"
        }
        require(authenticationIssuer.isNotBlank()) { "authentication.issuer must not be blank" }
        require(authenticationAudience.isNotBlank()) { "authentication.audience must not be blank" }
    }

    fun generateKey(size: Int) = Base64.encode(ByteArray(size).also { SecureRandom().nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!
    fun hashToken(token: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))


    fun generateToken(user: User): String {
        val now = Instant.now()
        return encoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().run {
                    issuedAt(now)
                    expiresAt(now.plus(authenticationTerm))
                    issuer(authenticationIssuer)
                    audience(listOf(authenticationAudience))
                    subject(user.id.toString())
                    build()
                }
            )
        ).tokenValue
    }
}
