package org.kert0n.medappserver.services.security

import java.util.UUID
import org.kert0n.medappserver.services.aggregate.UserService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Точка, где Spring Security спрашивает про пользователя.
 *
 * Обёртки над доменным типом здесь нет: `UserDetails` реализует сам `domain.User`, поэтому
 * остаётся только спросить агрегат. Через сервис, а не через хранилище: до агрегата ходят
 * только его сервисом, и адаптер безопасности не исключение.
 */
@Service
class AuthenticatedUserService(private val users: UserService) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails =
        users.findById(UUID.fromString(username)) ?: throw UsernameNotFoundException(username)
}
