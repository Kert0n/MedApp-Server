package org.kert0n.medappserver.db.store

import java.util.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.kert0n.medappserver.db.tables.Users
import org.kert0n.medappserver.domain.User
import org.springframework.stereotype.Component

/** Хранилище пользователя. */
@Component
class UserStore {

    fun findById(userId: UUID): User? =
        Users.selectAll().where { Users.id eq userId }
            .singleOrNull()
            ?.let { User(it[Users.id], it[Users.hashedKey]) }

    fun insert(user: User) {
        Users.insert {
            it[id] = user.id
            it[hashedKey] = user.hashedKey
        }
    }
}
