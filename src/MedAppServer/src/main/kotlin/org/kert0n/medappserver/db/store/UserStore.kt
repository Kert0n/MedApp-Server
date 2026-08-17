package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.UserData
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.User
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** Хранилище пользователя. */
@Component
class UserStore(private val users: UserRepository) {

    fun findById(userId: UUID): User? =
        users.findByIdOrNull(userId)?.let { User(it.id, it.hashedKey) }

    fun insert(user: User) {
        users.save(UserData(id = user.id, hashedKey = user.hashedKey))
    }
}
