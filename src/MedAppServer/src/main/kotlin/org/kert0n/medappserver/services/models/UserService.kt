package org.kert0n.medappserver.services.models

import java.util.UUID
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class UserService(
    private val users: UserStore,
    private val securityService: SecurityService
) {

    private val logger = LoggerFactory.getLogger(UserService::class.java)

    @Transactional
    fun registerNewUser(login: UUID, password: String, ip: String): User {
        logger.debug("Register new user {}", login)
        val user = User(id = login, hashedKey = securityService.hashPassword(password))
        users.insert(user)
        securityService.registerIncrease(ip)
        return user
    }

    @Transactional(readOnly = true)
    fun requireById(id: UUID): User =
        findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

    @Transactional(readOnly = true)
    fun findById(id: UUID): User? {
        logger.debug("Find user by id {}", id)
        return users.findById(id)
    }
}

val Authentication.userId: UUID
    get() = UUID.fromString(this.name)
