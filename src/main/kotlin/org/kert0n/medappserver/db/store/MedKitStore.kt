package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.MedKits
import org.kert0n.medappserver.domain.MedKit
import org.springframework.stereotype.Component

/**
 * Хранилище аптечек и отдельных строк членства.
 *
 * Аптечка больше не владеет снимком всех участников: вступление вставляет одну строку, выход
 * удаляет одну строку. Полный состав наружу не поднимается; чтения считают только публичный
 * `userCount`.
 */
@Component
class MedKitStore {

    // ── Чтения: принимают вызывающего, скоуп накладывает запрос ──────────────────

    fun find(medKitId: Uuid, userId: Uuid): MedKit? =
        withCaller(userId)
            .andWhere { MedKits.id eq medKitId }
            .singleOrNull()
            ?.let { MedKit(it[MedKits.id], it[memberCount]) }

    fun findAllOfUser(userId: Uuid): List<MedKit> =
        withCaller(userId)
            .orderBy(MedKits.id)
            .map { MedKit(it[MedKits.id], it[memberCount]) }

    // ── Команды: меняют одну строку, а не снимок всего множества ─────────────────

    fun insert(medKit: MedKit, firstMember: Uuid) {
        MedKits.insert { it[id] = medKit.id }
        insertMembership(medKit.id, firstMember)
    }

    fun insertMembership(medKitId: Uuid, userId: Uuid) {
        translatingConstraints {
            MedKitMemberships.insert {
                it[MedKitMemberships.medKitId] = medKitId
                it[MedKitMemberships.userId] = userId
            }
        }
    }

    internal fun insertMembership(medKit: MedKit, userId: Uuid) = insertMembership(medKit.id, userId)

    fun deleteMembership(medKitId: Uuid, userId: Uuid) {
        MedKitMemberships.deleteWhere {
            (MedKitMemberships.medKitId eq medKitId) and (MedKitMemberships.userId eq userId)
        }
    }

    internal fun deleteMembership(medKit: MedKit, userId: Uuid) = deleteMembership(medKit.id, userId)

    fun hasMembers(medKitId: Uuid): Boolean =
        !MedKitMemberships.selectAll()
            .where { MedKitMemberships.medKitId eq medKitId }
            .limit(1)
            .empty()

    internal fun hasMembers(medKit: MedKit): Boolean = hasMembers(medKit.id)

    /** Содержимое и membership удаляют каскадные ключи. */
    fun delete(medKitId: Uuid) {
        MedKits.deleteWhere { MedKits.id eq medKitId }
    }

    internal fun delete(medKit: MedKit) = delete(medKit.id)

    /**
     * Единственный блокирующий запрос приложения — в двух режимах.
     *
     * [RootLock.EXCLUSIVE] сериализует изменения состава участников и переезд упаковок между
     * аптечками. [RootLock.SHARED] совместим сам с собой, поэтому обычные записи друг друга не
     * задерживают, но ждут жизненного цикла — и он ждёт их.
     *
     * Блокируется только `med_kits` (`OF`): присоединённая строка membership участвует как
     * доказательство доступа, а не как предмет блокировки, — иначе исключительный режим
     * конфликтовал бы с внешними ключами чужих броней.
     *
     * Все вызывающие блокируют корни в порядке UUID. Поэтому два действия над парой аптечек
     * не берут те же строки в обратном порядке. Предикат membership одновременно доказывает
     * доступ; неполный результат снаружи трактуется так же, как обычная закрытая находка.
     */
    internal fun lockAccessible(medKitIds: Set<Uuid>, userId: Uuid, lock: RootLock): Set<Uuid> {
        if (medKitIds.isEmpty()) return emptySet()

        val mine = MedKitMemberships.alias("mine_to_lock")
        return MedKits
            .join(mine, JoinType.INNER, MedKits.id, mine[MedKitMemberships.medKitId])
            .select(MedKits.id)
            .where { (MedKits.id inList medKitIds) and (mine[MedKitMemberships.userId] eq userId) }
            .orderBy(MedKits.id to SortOrder.ASC)
            .forUpdate(lock.option)
            .map { it[MedKits.id] }
            .toSet()
    }

    /**
     * Повторная проверка membership после получения корневых блокировок.
     *
     * Locking SELECT начинает statement до ожидания и способен увидеть присоединённую строку
     * membership из старого снимка. Новый statement уже видит commit, которого ждали корни.
     */
    internal fun accessible(medKitIds: Set<Uuid>, userId: Uuid): Set<Uuid> =
        if (medKitIds.isEmpty()) emptySet()
        else MedKitMemberships
            .select(MedKitMemberships.medKitId)
            .where {
                (MedKitMemberships.medKitId inList medKitIds) and
                    (MedKitMemberships.userId eq userId)
            }
            .map { it[MedKitMemberships.medKitId] }
            .toSet()

    // ── Внутреннее ──────────────────────────────────────────────────────────────

    private val memberCount = MedKitMemberships.userId.count()

    /**
     * Аптечки вызывающего со счётчиком, но без идентификаторов остальных участников.
     * `mine` отвечает только за доступ, а второе соединение — за агрегатный счётчик.
     */
    private fun withCaller(userId: Uuid): Query {
        val mine = MedKitMemberships.alias("mine")
        return MedKits
            .join(mine, JoinType.INNER, MedKits.id, mine[MedKitMemberships.medKitId])
            .join(MedKitMemberships, JoinType.INNER, MedKits.id, MedKitMemberships.medKitId)
            .select(MedKits.id, memberCount)
            .where { mine[MedKitMemberships.userId] eq userId }
            .groupBy(MedKits.id)
    }
}

/**
 * Насколько сильно команда держит корень аптечки.
 *
 * Два режима, а не «блокировать или нет»: слабый нужен там, где запись ссылается на строку,
 * которую жизненный цикл сносит, и защититься версией нечем. Сам с собой он совместим, так
 * что обычные команды из-за него в очередь не выстраиваются.
 */
internal enum class RootLock(internal val option: ForUpdateOption) {

    /** Меняется состав участников или место упаковок: никто другой корень одновременно не держит. */
    EXCLUSIVE(ForUpdateOption.PostgreSQL.ForUpdate(null, MedKits)),

    /** Пишется содержимое аптечки: состав участников на время записи не сдвинется. */
    SHARED(ForUpdateOption.PostgreSQL.ForKeyShare(null, MedKits))
}
