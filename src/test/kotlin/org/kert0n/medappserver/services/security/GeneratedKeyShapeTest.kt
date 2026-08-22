package org.kert0n.medappserver.services.security

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Форма выдаваемых ключей.
 *
 * Ключ ходит в заголовке Basic, в ссылках-приглашениях и через копирование руками, а обычный
 * Base64 даёт `=` на конце каждого 32-байтного ключа и `+` с `/` в алфавите — их пришлось бы
 * экранировать в URL и в оболочке.
 *
 * Проверяются алфавит и длина, а не случайность: ГПСЧ здесь SecureRandom.
 */
@SpringBootTest
@ActiveProfiles("test")
class GeneratedKeyShapeTest {

    @Autowired
    private lateinit var securityService: SecurityService

    @Test
    fun `ключ не содержит выравнивания и небезопасных для URL символов`() {
        val allowed = Regex("^[A-Za-z0-9_-]+$")
        repeat(200) {
            val key = securityService.generateKey(32)
            assertTrue(allowed.matches(key), "ключ содержит посторонние символы: $key")
        }
    }

    @Test
    fun `длина соответствует запрошенной энтропии`() {
        // 32 байта без выравнивания — 43 символа: ceil(32 * 8 / 6). С выравниванием их 44, и
        // последний всегда '='.
        assertEquals(43, securityService.generateKey(32).length)
        assertEquals(22, securityService.generateKey(16).length)
    }
}
