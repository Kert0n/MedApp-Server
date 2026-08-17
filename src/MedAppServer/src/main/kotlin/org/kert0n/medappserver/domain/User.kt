package org.kert0n.medappserver.domain

import java.util.UUID
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Пользователь.
 *
 * Правило здесь ровно одно, но оно есть: пользователя без ключа не существует. Пароль в
 * открытом виде сюда не доходит — хеширование остаётся в слое безопасности, это вопрос
 * алгоритма, а не предметной области.
 *
 * `UserDetails` реализован здесь же. Раньше это делала строка таблицы, и способ
 * аутентификации протекал в отображение; теперь он опирается на домен, а отображение о
 * Spring Security не знает. Цена — зависимость домена от `spring-security-core`, принятая
 * сознательно: пользователь и есть тот, кем представляется система аутентификации.
 */
data class User(
    val id: UUID = UUID.randomUUID(),
    val hashedKey: String
) : UserDetails {

    init {
        if (hashedKey.isBlank()) throw InvalidCredentials()
    }

    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getPassword(): String = hashedKey

    override fun getUsername(): String = id.toString()

    /** Пользователь — сущность: смена ключа не делает его другим пользователем. */
    override fun equals(other: Any?): Boolean = this === other || (other is User && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
