package org.kert0n.medappserver.db.store

import java.util.UUID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.MedKits
import org.kert0n.medappserver.domain.MedKit
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата аптечки.
 *
 * Состояние аптечки — это её идентификатор и множество участников, поэтому запись сводится
 * к сведению строк членства: появившиеся вставляются, исчезнувшие удаляются одним запросом.
 */
@Component
class MedKitStore {

    /**
     * Аптечка, доступная вызывающему, — одним запросом.
     *
     * Доступ проверяет сам запрос: не нашли — значит либо нет аптечки, либо вызывающего нет в
     * ней, и различать эти случаи мы не собираемся. Состав приходит целиком: агрегат аптечки и
     * есть её состав, а решения по нему принимает не только тот, кто её читал.
     */
    fun find(medKitId: UUID, userId: UUID): MedKit? {
        val mine = MedKitMemberships.selectAll()
            .where { (MedKitMemberships.medKitId eq medKitId) and (MedKitMemberships.userId eq userId) }

        val members = MedKitMemberships.selectAll()
            .where { (MedKitMemberships.medKitId eq medKitId) and exists(mine) }
            .map { it[MedKitMemberships.userId] }
            .toSet()

        return if (members.isEmpty()) null else MedKit(medKitId, members)
    }

    /**
     * Все аптечки участника — агрегатами и одним запросом.
     *
     * Берутся строки членства тех аптечек, где состоит вызывающий, и группируются в памяти:
     * состав нужен целиком, а не только его строка.
     */
    fun findAllOfUser(userId: UUID): List<MedKit> {
        val mineKits = MedKitMemberships.select(MedKitMemberships.medKitId)
            .where { MedKitMemberships.userId eq userId }

        return MedKitMemberships.selectAll()
            .where { MedKitMemberships.medKitId inSubQuery mineKits }
            .groupBy({ it[MedKitMemberships.medKitId] }, { it[MedKitMemberships.userId] })
            .map { (id, members) -> MedKit(id, members.toSet()) }
            .sortedBy { it.id }
    }

    fun insert(medKit: MedKit) {
        MedKits.insert { it[id] = medKit.id }
        addMembers(medKit.id, medKit.members)
    }

    /** Сводит строки членства к тому, что в состоянии. Сама аптечка полей больше не имеет. */
    fun save(medKit: MedKit) {
        val stored = MedKitMemberships.selectAll()
            .where { MedKitMemberships.medKitId eq medKit.id }
            .map { it[MedKitMemberships.userId] }
            .toSet()

        val gone = stored - medKit.members
        if (gone.isNotEmpty()) {
            MedKitMemberships.deleteWhere {
                (MedKitMemberships.medKitId eq medKit.id) and (MedKitMemberships.userId inList gone)
            }
        }
        addMembers(medKit.id, medKit.members - stored)
    }

    /**
     * Вставляет строку членства, не читая состав аптечки.
     *
     * Обслуживает вступление по приглашению: вызывающего в аптечке ещё нет, и скоупленное
     * чтение его туда не пустит. Правило «дважды не вступают» проверяет `MedKitService`.
     */
    fun addMember(medKitId: UUID, userId: UUID) = addMembers(medKitId, setOf(userId))

    fun isMember(medKitId: UUID, userId: UUID): Boolean =
        MedKitMemberships.selectAll()
            .where { (MedKitMemberships.medKitId eq medKitId) and (MedKitMemberships.userId eq userId) }
            .empty()
            .not()

    /**
     * Удаление аптечки.
     *
     * Упаковки и членство уносит каскад внешнего ключа. Ничего очищать в памяти не нужно:
     * загруженных строк, которые могли бы разъехаться с базой, здесь просто не бывает.
     */
    fun delete(medKit: MedKit) {
        MedKits.deleteWhere { MedKits.id eq medKit.id }
    }

    private fun addMembers(medKitId: UUID, userIds: Set<UUID>) {
        if (userIds.isEmpty()) return
        MedKitMemberships.batchInsert(userIds) { member ->
            this[MedKitMemberships.medKitId] = medKitId
            this[MedKitMemberships.userId] = member
        }
    }
}
