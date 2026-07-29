package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
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

    fun registerNewUser(ip: String): NewCredentials {
        if (!securityService.isRegistrationAllowed(ip)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registration request")
        }

        val login = UUID.randomUUID()
        val key = securityService.generateKey(32)
        logger.debug("Register new user $login")
        userRepository.save(User(login, securityService.hashPassword(key)))
        securityService.recordRegisterAttempt(ip)
        return NewCredentials(login, key)
    }


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
