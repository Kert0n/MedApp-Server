package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.UserData
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<UserData, UUID>
