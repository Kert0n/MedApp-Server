package org.kert0n.medappserver.services.security

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.User
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecurityServiceTest {
    private val passwordEncoder = mock<PasswordEncoder>()
    private val jwtEncoder = mock<JwtEncoder>()

    @Test
    fun `configuration rejects invalid authentication values`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityService(passwordEncoder, jwtEncoder, Duration.ZERO, "issuer", "audience")
        }
        assertFailsWith<IllegalArgumentException> {
            SecurityService(passwordEncoder, jwtEncoder, Duration.ofMinutes(10), " ", "audience")
        }
        assertFailsWith<IllegalArgumentException> {
            SecurityService(passwordEncoder, jwtEncoder, Duration.ofMinutes(10), "issuer", " ")
        }
    }

    @Test
    fun `generated token contains configured identity and lifetime claims`() {
        val encodedJwt = mock<Jwt>()
        whenever(encodedJwt.tokenValue).thenReturn("encoded-token")
        whenever(jwtEncoder.encode(any())).thenReturn(encodedJwt)
        val service = SecurityService(
            passwordEncoder,
            jwtEncoder,
            Duration.ofMinutes(10),
            "test-issuer",
            "test-audience"
        )
        val user = User(id = UUID.randomUUID(), hashedKey = "hash")
        val before = Instant.now()

        assertEquals("encoded-token", service.generateToken(user))

        val parameters = argumentCaptor<JwtEncoderParameters>()
        verify(jwtEncoder).encode(parameters.capture())
        val claims = parameters.firstValue.claims
        assertEquals(user.id.toString(), claims.subject)
        assertEquals("test-issuer", claims.getClaimAsString("iss"))
        assertEquals(listOf("test-audience"), claims.audience)
        assertFalse(claims.issuedAt!!.isBefore(before))
        assertEquals(Duration.ofMinutes(10), Duration.between(claims.issuedAt, claims.expiresAt))
    }
}
