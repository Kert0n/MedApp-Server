package org.kert0n.medappserver.services.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

data class AuthenticatedUser(
    val id: UUID,
    private val hashedKey: String
) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()
    override fun getPassword(): String = hashedKey
    override fun getUsername(): String = id.toString()
}
