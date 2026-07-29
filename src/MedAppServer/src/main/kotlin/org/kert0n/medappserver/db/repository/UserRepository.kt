package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {
}
