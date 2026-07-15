package org.kert0n.medappserver.services.models

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class UserServiceTest {
    private val repository = mock<UserRepository>()
    private val service = UserService(repository, mock<SecurityService>())

    @Test
    fun `authentication returns persistence user directly`() {
        val user = User(id = UUID.randomUUID(), hashedKey = "{noop}password")
        whenever(repository.findById(user.id)).thenReturn(Optional.of(user))

        val principal = service.loadUserByUsername(user.id.toString())

        assertSame(user, principal)
        assertEquals(user.id.toString(), principal.username)
        assertEquals(user.hashedKey, principal.password)
        assertEquals(emptyList(), principal.authorities)
    }

    @Test
    fun `malformed and unknown identifiers return the same authentication error`() {
        val malformed = assertFailsWith<UsernameNotFoundException> {
            service.loadUserByUsername("not-a-uuid")
        }
        verifyNoInteractions(repository)

        val missingId = UUID.randomUUID()
        whenever(repository.findById(missingId)).thenReturn(Optional.empty())
        val missing = assertFailsWith<UsernameNotFoundException> {
            service.loadUserByUsername(missingId.toString())
        }

        assertEquals("Invalid credentials", malformed.message)
        assertEquals(malformed.message, missing.message)
    }
}
