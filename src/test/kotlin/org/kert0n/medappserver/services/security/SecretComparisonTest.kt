package org.kert0n.medappserver.services.security

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Правильность сравнения секретов за постоянное время: обычное `!=` остановилось бы на первом
 * несовпавшем символе. Само свойство постоянного времени модульным тестом надёжно не
 * утверждается — оно описано на [SecurityService.secretsMatch].
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
