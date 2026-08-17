package org.kert0n.medappserver.domain.medkit

import java.util.UUID
import org.kert0n.medappserver.domain.error.AlreadyMember
import org.kert0n.medappserver.domain.error.NotAMember

/**
 * Аптечка — корень агрегата членства.
 *
 * Препаратов внутри нет: препарат сам знает, в какой он аптечке, и меняется своими
 * правилами. Здесь живёт только то, что нельзя решить, глядя на одну строку: кто участник,
 * можно ли войти, и что происходит, когда выходит последний.
 */
@ConsistentCopyVisibility
data class MedKit private constructor(
    val id: UUID,
    val members: Set<UUID>
) {

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
     * Признак `becameEmpty` — то самое решение, ради которого аптечка вообще агрегат:
     * аптечки без участников не бывает, поэтому последний выходящий забирает её с собой.
     * Удаляет строку вызывающий — это работа хранилища.
     */
    fun leave(userId: UUID): LeaveOutcome {
        if (!isMember(userId)) throw NotAMember()
        val left = copy(members = members - userId)
        return LeaveOutcome(left, becameEmpty = left.members.isEmpty())
    }

    companion object {
        /** Новая аптечка всегда заводится вместе со своим первым участником. */
        fun create(ownerId: UUID, id: UUID = UUID.randomUUID()): MedKit =
            MedKit(id = id, members = setOf(ownerId))

        /** Восстановление из хранилища: проверки входа здесь не повторяются. */
        fun fromStored(id: UUID, members: Set<UUID>): MedKit = MedKit(id, members)
    }
}

/** Что осталось после выхода участника. */
data class LeaveOutcome(
    val medKit: MedKit,
    /** Участников не осталось: аптечку надо удалить вместе с её содержимым. */
    val becameEmpty: Boolean
)

/**
 * Счётчики аптечки для списка.
 *
 * Отдельное значение, а не сам агрегат: чтобы показать «участников 3, препаратов 12», не
 * нужно поднимать ни участников, ни препараты, и поднимать их ради счётчика было бы ровно
 * тем, чего агрегатные границы избегают.
 */
data class MedKitOverview(
    val id: UUID,
    val memberCount: Long,
    val drugCount: Long
)
