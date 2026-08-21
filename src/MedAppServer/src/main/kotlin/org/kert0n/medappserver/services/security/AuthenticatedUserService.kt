package org.kert0n.medappserver.services.security

import java.util.UUID
import org.kert0n.medappserver.services.aggregate.UserService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Точка, где Spring Security спрашивает про пользователя.
 *
 * Обёртки над доменным типом здесь нет: `UserDetails` реализует сам `domain.User`, поэтому
 * остаётся только спросить агрегат. Через сервис, а не через хранилище: до агрегата ходят
 * только его сервисом, и адаптер безопасности не исключение.
 *
 * Транзакцию открывает сам: зовёт его фильтр безопасности, и никакой границы вокруг ещё нет.
 * Это точка входа наравне с фасадом, просто вход не из контроллера.
 */
@Service
class AuthenticatedUserService(private val users: UserService) : UserDetailsService {

    @Transactional(readOnly = true)
    override fun loadUserByUsername(username: String): UserDetails =
        users.findById(UUID.fromString(username)) ?: throw UsernameNotFoundException(username)
}
