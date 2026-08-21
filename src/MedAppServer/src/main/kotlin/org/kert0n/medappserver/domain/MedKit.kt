package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid

/**
 * Аптечка — корень агрегата членства.
 *
 * Упаковок внутри нет: упаковка сама знает, в какой она аптечке. Здесь только то, чего не
 * решить по одной строке: кто участник, можно ли войти, что будет, когда выйдет последний.
 */
data class MedKit(
    val id: Uuid = Uuid.random(),
    val members: Set<Uuid>
) {

    init {
        if (members.isEmpty()) throw MedKitWithoutMembers()
    }

    fun isMember(userId: Uuid): Boolean = userId in members

    /** Вступление по приглашению. Повторное — отказ: вступать второй раз некуда. */
    fun join(userId: Uuid): MedKit {
        if (isMember(userId)) throw AlreadyMember()
        return copy(members = members + userId)
    }

    /** `null` — вышел последний: аптечки без участников не бывает, и она уходит вместе с ним. */
    fun leave(userId: Uuid): MedKit? {
        if (!isMember(userId)) throw NotAMember()
        val left = members - userId
        return if (left.isEmpty()) null else copy(members = left)
    }

    /** Сущность: набор участников меняется, аптечка остаётся той же. */
    override fun equals(other: Any?): Boolean = this === other || (other is MedKit && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

