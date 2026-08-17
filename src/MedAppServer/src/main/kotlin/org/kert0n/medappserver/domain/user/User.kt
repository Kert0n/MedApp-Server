package org.kert0n.medappserver.domain.user

import java.util.UUID
import org.kert0n.medappserver.domain.error.InvalidCredentials

/**
 * Пользователь.
 *
 * Правило здесь ровно одно, но оно есть: пользователь без ключа не существует. Пароль в
 * открытом виде до этого типа не доходит — хеширование остаётся снаружи, в слое
 * безопасности, потому что это вопрос алгоритма, а не предметной области.
 */
@ConsistentCopyVisibility
data class User private constructor(
    val id: UUID,
    val hashedKey: String
) {
    companion object {
        fun register(hashedKey: String, id: UUID = UUID.randomUUID()): User {
            if (hashedKey.isBlank()) throw InvalidCredentials()
            return User(id, hashedKey)
        }

        fun fromStored(id: UUID, hashedKey: String): User = User(id, hashedKey)
    }
}
