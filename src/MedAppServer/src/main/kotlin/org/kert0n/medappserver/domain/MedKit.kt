package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Аптечка — корень агрегата членства.
 *
 * Упаковок внутри нет: упаковка сама знает, в какой она аптечке. Здесь только то, чего не
 * решить по одной строке: кто участник, можно ли войти, что будет, когда выйдет последний.
 */
data class MedKit(
    val id: UUID = UUID.randomUUID(),
    val members: Set<UUID>,
    /** Непрозрачный токен состояния: домен его не толкует и не двигает, этим занят Hibernate. */
    val version: Long = 0
) {

    init {
        if (members.isEmpty()) throw MedKitWithoutMembers()
    }

    /** Клиент решал по этому составу — или по устаревшему. */
    fun requireVersion(expected: Long) {
        if (expected != version) throw StaleAggregateVersion()
    }

    fun isMember(userId: UUID): Boolean = userId in members

    /**
     * Доступ есть только у участника.
     *
     * Вызывающий обязан отобразить «не участник» и «нет аптечки» одинаково: иначе по коду
     * ответа узнаётся, что чужая аптечка существует.
     */
    fun requireMember(userId: UUID) {
        if (!isMember(userId)) throw NotAMember()
    }

    /** Вступление по приглашению. Повторное — ошибка: клиент уже внутри. */
    fun join(userId: UUID): MedKit {
        if (isMember(userId)) throw AlreadyMember()
        return copy(members = members + userId)
    }

    /** `null` — вышел последний: аптечки без участников не бывает, и она уходит вместе с ним. */
    fun leave(userId: UUID): MedKit? {
        if (!isMember(userId)) throw NotAMember()
        val left = members - userId
        return if (left.isEmpty()) null else copy(members = left)
    }

    /** Сущность: набор участников меняется, аптечка остаётся той же. */
    override fun equals(other: Any?): Boolean = this === other || (other is MedKit && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

