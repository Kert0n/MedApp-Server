package org.kert0n.medappserver.services.security

import java.util.UUID
import org.kert0n.medappserver.db.store.UserStore
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Точка, где Spring Security спрашивает про пользователя.
 *
 * Обёртки над доменным типом здесь нет: `UserDetails` реализует сам `domain.User`, поэтому
 * сервису остаётся только сходить в хранилище.
 */
@Service
class AuthenticatedUserService(private val users: UserStore) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails =
        users.findById(UUID.fromString(username)) ?: throw UsernameNotFoundException(username)
}
