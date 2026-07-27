package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val securityService: SecurityService
) : UserDetailsService {

    private val logger = LoggerFactory.getLogger(UserService::class.java)

    fun registerNewUser(login: UUID, password: String, ip: String): User {
        logger.debug("Register new user $login")
        val user = userRepository.save(
            User(login, securityService.hashPassword(password))
        )
        securityService.registerIncrease(ip)
        return user
    }

    /**
     * Логин у нас — это UUID, но приходит он строкой из заголовка Basic, то есть от кого
     * угодно и в любом виде.
     *
     * `UUID.fromString` на мусоре бросает `IllegalArgumentException`, и Spring Security
     * заворачивает его в `InternalAuthenticationServiceException`. Статус наружу при этом
     * оставался верным — 401, потому что это всё-таки `AuthenticationException`, — но
     * каждый такой запрос печатал в лог полный стектрейс как внутреннюю ошибку. То есть
     * любой неаутентифицированный клиент мог одной строкой в заголовке заставить сервер
     * писать стектрейсы, а дежурного — искать несуществующий сбой.
     *
     * Неразобранный логин — это «такого пользователя нет», а не сбой: тот же
     * [UsernameNotFoundException], что и для несуществующего UUID.
     */
    override fun loadUserByUsername(username: String): UserDetails {
        logger.debug("Load user $username")
        val id = runCatching { UUID.fromString(username) }.getOrNull()
            ?: throw UsernameNotFoundException(username)
        return userRepository.findByIdOrNull(id) ?: throw UsernameNotFoundException(username)
    }

    fun findById(id: UUID): User {
        logger.debug("Find user by id $id")
        return userRepository.findByIdOrNull(id) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "User with ID $id not found"
        )
    }



}

val Authentication.userId: UUID
    get() = UUID.fromString(this.name)