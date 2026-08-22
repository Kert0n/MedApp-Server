package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Пользователь: идентификатор и хеш ключа, больше о человеке ничего не хранится.
 *
 * Правило одно — пользователя без ключа не существует. Открытый пароль сюда не доходит:
 * хеширование остаётся в слое безопасности.
 *
 * `UserDetails` реализован здесь, и это принятая цена: домен зависит от
 * `spring-security-core`, зато отображение о нём не знает.
 */
data class User(
    val id: Uuid = Uuid.random(),
    val hashedKey: String
) : UserDetails {

    init {
        if (hashedKey.isBlank()) throw InvalidCredentials()
    }

    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getPassword(): String = hashedKey

    override fun getUsername(): String = id.toString()

    /** Сущность: смена ключа не делает его другим пользователем. */
    override fun equals(other: Any?): Boolean = this === other || (other is User && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
