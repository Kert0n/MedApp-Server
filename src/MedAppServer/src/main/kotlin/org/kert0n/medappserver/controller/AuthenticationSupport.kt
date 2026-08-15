package org.kert0n.medappserver.controller

import org.springframework.security.core.Authentication
import java.util.UUID

val Authentication.userId: UUID
    get() = UUID.fromString(name)
