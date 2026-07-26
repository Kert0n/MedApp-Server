package org.kert0n.medappserver.services.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registration secret used to be compared with `!=`, which stops at the first differing
 * character. This covers correctness of the replacement; the timing property itself is not
 * something a unit test can assert reliably, so it is documented on [SecurityService.secretsMatch].
 */
@SpringBootTest
@ActiveProfiles("test")
class SecretComparisonTest {

    @Autowired
    private lateinit var securityService: SecurityService

    @Test
    fun `identical secrets match`() {
        assertTrue(securityService.secretsMatch("s3cret-value", "s3cret-value"))
    }

    @Test
    fun `different secrets of equal length do not match`() {
        assertFalse(securityService.secretsMatch("s3cret-value", "s3cret-valuf"))
    }

    @Test
    fun `secrets differing only in length do not match`() {
        assertFalse(securityService.secretsMatch("s3cret", "s3cret-value"))
        assertFalse(securityService.secretsMatch("s3cret-value", "s3cret"))
    }

    @Test
    fun `a correct prefix is not accepted`() {
        assertFalse(securityService.secretsMatch("s3cret-valu", "s3cret-value"))
    }

    @Test
    fun `empty candidate does not match a real secret`() {
        assertFalse(securityService.secretsMatch("", "s3cret-value"))
    }
}
