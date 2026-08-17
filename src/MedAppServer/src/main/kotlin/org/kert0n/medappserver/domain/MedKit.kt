package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Аптечка — корень агрегата членства.
 *
 * Препаратов внутри нет: препарат сам знает, в какой он аптечке, и меняется своими
 * правилами. Здесь живёт только то, чего не решить, глядя на одну строку: кто участник,
 * можно ли войти, и что происходит, когда выходит последний.
 */
data class MedKit(
    val id: UUID = UUID.randomUUID(),
    val members: Set<UUID>
) {

    init {
        if (members.isEmpty()) throw MedKitWithoutMembers()
    }

    fun isMember(userId: UUID): Boolean = userId in members

    /**
     * Доступ есть только у участника.
     *
     * Отсутствие членства и отсутствие самой аптечки вызывающий обязан отобразить одинаково:
     * иначе по коду ответа можно узнать, что чужая аптечка существует.
     */
    fun requireMember(userId: UUID) {
        if (!isMember(userId)) throw NotAMember()
    }

    /** Вступление по приглашению. Повторное — ошибка: клиент уже внутри. */
    fun join(userId: UUID): MedKit {
        if (isMember(userId)) throw AlreadyMember()
        return copy(members = members + userId)
    }

    /**
     * Выход участника.
     *
     * `null` означает, что вышел последний: аптечки без участников не бывает, поэтому она
     * уходит вместе с ним — и такого состояния агрегат не строит вовсе. Удаляет строку
     * вызывающий, это работа хранилища.
     */
    fun leave(userId: UUID): MedKit? {
        if (!isMember(userId)) throw NotAMember()
        val left = members - userId
        return if (left.isEmpty()) null else copy(members = left)
    }

    /** Аптечка — сущность: набор участников меняется, аптечка остаётся той же. */
    override fun equals(other: Any?): Boolean = this === other || (other is MedKit && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

