package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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

    /** То же чтение для уже известного небольшого набора, после блокировки его корней. */
    fun findAll(medKitIds: Set<Uuid>, userId: Uuid): List<MedKit> =
        if (medKitIds.isEmpty()) emptyList()
        else withCaller(userId)
            .andWhere { MedKits.id inList medKitIds }
            .orderBy(MedKits.id)
            .map { MedKit(it[MedKits.id], it[memberCount]) }

    // ── Команды: меняют одну строку, а не снимок всего множества ─────────────────

    fun insert(medKit: MedKit, firstMember: Uuid) {
        MedKits.insert { it[id] = medKit.id }
        insertMembership(medKit, firstMember)
    }

    fun insertMembership(medKit: MedKit, userId: Uuid) {
        translatingConstraints {
            MedKitMemberships.insert {
                it[medKitId] = medKit.id
                it[MedKitMemberships.userId] = userId
            }
        }
    }

    fun deleteMembership(medKit: MedKit, userId: Uuid) {
        MedKitMemberships.deleteWhere {
            (MedKitMemberships.medKitId eq medKit.id) and (MedKitMemberships.userId eq userId)
        }
    }

    fun hasMembers(medKit: MedKit): Boolean =
        !MedKitMemberships.selectAll()
            .where { MedKitMemberships.medKitId eq medKit.id }
            .limit(1)
            .empty()

    /** Содержимое и membership удаляют каскадные ключи. */
    fun delete(medKit: MedKit) {
        MedKits.deleteWhere { MedKits.id eq medKit.id }
    }

    /**
     * Сериализует только редкие изменения жизненного цикла membership.
     *
     * Все вызывающие блокируют корни в порядке UUID. Поэтому два действия над парой аптечек
     * не берут те же строки в обратном порядке. Предикат membership одновременно доказывает
     * доступ; неполный результат снаружи трактуется так же, как обычная закрытая находка.
     */
    fun lockAccessible(medKitIds: Set<Uuid>, userId: Uuid): Set<Uuid> {
        if (medKitIds.isEmpty()) return emptySet()

        val mine = MedKitMemberships.alias("mine_to_lock")
        return MedKits
            .join(mine, JoinType.INNER, MedKits.id, mine[MedKitMemberships.medKitId])
            .select(MedKits.id)
            .where { (MedKits.id inList medKitIds) and (mine[MedKitMemberships.userId] eq userId) }
            .orderBy(MedKits.id to SortOrder.ASC)
            .forUpdate()
            .map { it[MedKits.id] }
            .toSet()
    }

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
