package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.Logger
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
    private val securityService: SecurityService,
    private val logger: Logger = LoggerFactory.getLogger(UserService::class.java)
) : UserDetailsService {

    fun registerNewUser(login: UUID, password: String, ip: String): User {
        logger.debug("Register new user $login")
        val user = userRepository.save(
            User(login, securityService.hashPassword(password))
        )
        securityService.registerIncrease(ip)
        return user
    }

    override fun loadUserByUsername(username: String): UserDetails {
        logger.debug("Load user $username")
        return userRepository.findByIdOrNull(UUID.fromString(username)) ?: throw UsernameNotFoundException(username)
    }

    fun findById(id: UUID): User {
        logger.debug("Find user by id $id")
        return userRepository.findByIdOrNull(id) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "User with ID $id not found"
        )
    }

    // fun findAllByDrug(drugId: UUID): Set<User> = userRepository.findByTreatmentPlansDrugId(drugId)


}

val Authentication.userId: UUID
    get() = UUID.fromString(this.name)