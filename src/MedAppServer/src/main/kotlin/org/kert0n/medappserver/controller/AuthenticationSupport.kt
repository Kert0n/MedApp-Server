package org.kert0n.medappserver.controller

import org.springframework.security.core.Authentication
import java.util.UUID

/**
 * Идентификатор пользователя из аутентификации запроса.
 *
 * Живёт в `controller`, а не в `services.models`, где лежал раньше: это разбор
 * HTTP-принципала, то есть забота границы. В сервисном слое расширение не звал никто —
 * только контроллеры, все четыре, — но самим своим присутствием оно заставляло
 * `UserService` импортировать Spring Security, а контроллеры — ходить за типом границы в
 * пакет моделей.
 *
 * `name` у JWT-аутентификации — это subject токена, куда `SecurityService.generateToken`
 * кладёт `user.id`. Строка разбирается обратно в [UUID] здесь, чтобы дальше по коду
 * идентификатор ходил типом, а не текстом.
 */
val Authentication.userId: UUID
    get() = UUID.fromString(name)
