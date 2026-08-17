package org.kert0n.medappserver.services.security

import java.util.UUID
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.user.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Пользователь глазами Spring Security.
 *
 * Раньше `UserDetails` реализовывала сама строка таблицы: способ аутентификации протекал в
 * отображение, и поменять его нельзя было, не тронув сущность. Теперь это адаптер — он
 * знает и про домен, и про фреймворк, а те друг про друга не знают.
 */
class AuthenticatedUser(val user: User) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()
    override fun getPassword(): String = user.hashedKey
    override fun getUsername(): String = user.id.toString()
}

@Service
class AuthenticatedUserService(private val users: UserStore) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails =
        users.findById(UUID.fromString(username))?.let(::AuthenticatedUser)
            ?: throw UsernameNotFoundException(username)
}
