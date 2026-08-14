package org.kert0n.medappserver.services.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Форма выдаваемых ключей.
 *
 * Ключ ходит в заголовке Basic, в ссылках-приглашениях и через копирование руками. Обычный
 * Base64 давал сразу три неудобства: 32 байта не кратны трём, поэтому **каждый** ключ
 * заканчивался на `=`, а в алфавите есть `+` и `/`, которые приходится экранировать в URL и
 * в оболочке.
 *
 * Проверяется алфавит и длина, а не случайность: ГПСЧ здесь SecureRandom, и проверять его
 * статистикой в наборе тестов бессмысленно.
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
        // 32 байта без выравнивания — 43 символа: ceil(32 * 8 / 6). С выравниванием было 44,
        // и последний символ всегда был '='.
        assertEquals(43, securityService.generateKey(32).length)
        assertEquals(22, securityService.generateKey(16).length)
    }
}
