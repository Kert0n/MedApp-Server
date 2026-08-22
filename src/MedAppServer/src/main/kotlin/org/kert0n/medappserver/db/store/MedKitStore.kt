package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.MedKits
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.StaleVersion
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
    fun find(medKitId: Uuid, userId: Uuid): MedKit? {
        val rows = withCaller(userId)
            .andWhere { MedKitMemberships.medKitId eq medKitId }
            .toList()

        if (rows.isEmpty()) return null
        return MedKit(
            id = medKitId,
            members = rows.map { it[MedKitMemberships.userId] }.toSet(),
            version = rows.first()[MedKits.version]
        )
    }

    /**
     * Все аптечки участника — агрегатами и одним запросом.
     *
     * Берутся строки членства тех аптечек, где состоит вызывающий, и группируются в памяти:
     * состав нужен целиком, а не только его строка.
     */
    fun findAllOfUser(userId: Uuid): List<MedKit> =
        withCaller(userId)
            .groupBy({ it[MedKitMemberships.medKitId] to it[MedKits.version] }, { it[MedKitMemberships.userId] })
            .map { (key, members) -> MedKit(key.first, members.toSet(), key.second) }
            .sortedBy { it.id }

    /**
     * Строки членства тех аптечек, где состоит вызывающий.
     *
     * Соединением таблицы с собой, а не подзапросом: вопрос «аптечки, где есть моя строка»
     * соединением и записывается, а как его выполнять — дело планировщика.
     */
    private fun withCaller(userId: Uuid): Query {
        val mine = MedKitMemberships.alias("mine")
        return MedKitMemberships
            .join(mine, JoinType.INNER, MedKitMemberships.medKitId, mine[MedKitMemberships.medKitId])
            .join(MedKits, JoinType.INNER, MedKitMemberships.medKitId, MedKits.id)
            .selectAll()
            .where { mine[MedKitMemberships.userId] eq userId }
    }

    fun insert(medKit: MedKit) {
        MedKits.insert {
            it[id] = medKit.id
            it[version] = medKit.version
        }
        insertMemberships(medKit.id, medKit.members)
    }

    /** Сводит строки членства к тому, что в состоянии. Сама аптечка полей больше не имеет. */
    fun save(medKit: MedKit): MedKit {
        val saved = bumpVersion(medKit)

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
        insertMemberships(medKit.id, medKit.members - stored)
        return saved
    }

    /**
     * Версию аптечки двигает отдельный оператор.
     *
     * Состав лежит в `user_med_kits`, и правка членства сама по себе строки `med_kits` не
     * трогает — значит и версию не сдвинула бы. Стоит первым: проигравший гонку обязан
     * остановиться до того, как тронет членство.
     */
    private fun bumpVersion(medKit: MedKit): MedKit {
        val next = medKit.copy(version = medKit.version + 1)
        val written = MedKits.update({ (MedKits.id eq medKit.id) and (MedKits.version eq medKit.version) }) {
            it[version] = next.version
        }
        if (written == 0) throw StaleVersion()
        return next
    }

    /**
     * Удаление аптечки.
     *
     * Упаковки и членство уносит каскад внешнего ключа. Ничего очищать в памяти не нужно:
     * загруженных строк, которые могли бы разъехаться с базой, здесь просто не бывает.
     */
    fun delete(medKit: MedKit) {
        val removed = MedKits.deleteWhere { (MedKits.id eq medKit.id) and (MedKits.version eq medKit.version) }
        if (removed == 0) throw StaleVersion()
    }

    private fun insertMemberships(medKitId: Uuid, userIds: Set<Uuid>) {
        if (userIds.isEmpty()) return
        translatingConstraints {
            MedKitMemberships.batchInsert(userIds) { member ->
                this[MedKitMemberships.medKitId] = medKitId
                this[MedKitMemberships.userId] = member
            }
        }
    }
}
