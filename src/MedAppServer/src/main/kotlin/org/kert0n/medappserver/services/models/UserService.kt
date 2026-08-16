package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.error.UserNotFound
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val securityService: SecurityService,
    private val logger: Logger = LoggerFactory.getLogger(UserService::class.java)
) : UserDetailsService {

    fun registerNewUser(login: UUID, password: String, ip: String): UUID {
        logger.debug("Register new user $login")
        val user = userRepository.save(
            User(login, securityService.hashPassword(password))
        )
        securityService.registerIncrease(ip)
        return user.id
    }

    /** Malformed Basic usernames are authentication failures, not server errors. */
    override fun loadUserByUsername(username: String): UserDetails {
        logger.debug("Load user $username")
        val id = runCatching { UUID.fromString(username) }.getOrNull()
            ?: throw UsernameNotFoundException(username)
        return userRepository.findByIdOrNull(id) ?: throw UsernameNotFoundException(username)
    }

    fun findById(id: UUID): User {
        logger.debug("Find user by id $id")
        return userRepository.findByIdOrNull(id) ?: throw UserNotFound(id)
    }
}
