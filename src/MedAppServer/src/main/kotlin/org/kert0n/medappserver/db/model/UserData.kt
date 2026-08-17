package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import java.util.*

/**
 * Отображение пользователя на таблицу `users`.
 *
 * Аптечек здесь нет — членство хранится строками [MedKitMembershipData]. Spring Security
 * тоже нет: `UserDetails` реализует адаптер в слое безопасности, а не строка таблицы.
 */
@Entity
@Table(
    name = "users",
    indexes = [
        Index(name = "ix_users_hashed_key", columnList = "hashed_key", unique = true)
    ]
)
class UserData(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),
    @NotNull
    @Column(name = "hashed_key", nullable = false)
    var hashedKey: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UserData

        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
