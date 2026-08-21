package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.MedKitMembershipData
import org.kert0n.medappserver.db.model.MedKitMembershipKey
import org.kert0n.medappserver.db.repository.MedKitMembershipRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.domain.MedKit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата аптечки.
 *
 * Состояние аптечки — это её идентификатор и множество участников, поэтому запись сводится
 * к сведению строк членства: появившиеся вставляются, исчезнувшие удаляются одним запросом.
 */
@Component
class MedKitStore(
    private val medKits: MedKitRepository,
    private val memberships: MedKitMembershipRepository
) {

    /**
     * Аптечка, доступная вызывающему, — одним запросом.
     *
     * Доступ проверяет сам запрос: не нашли — значит либо нет аптечки, либо вызывающего нет в
     * ней, и различать эти случаи мы не собираемся. Состав приходит целиком: агрегат аптечки и
     * есть её состав, а решения по нему принимает не только тот, кто её читал.
     */
    fun find(medKitId: UUID, userId: UUID): MedKit? {
        val rows = memberships.findMembershipsOf(medKitId, userId)
        if (rows.isEmpty()) return null
        return MedKit(medKitId, rows.map { it.membershipKey.userId }.toSet())
    }

    /**
     * Все аптечки участника — агрегатами и одним запросом.
     *
     * Строки членства приходят со своими аптечками, состав собирается группировкой в памяти.
     */
    fun findAllOfUser(userId: UUID): List<MedKit> =
        medKits.findMembershipsOfUserKits(userId)
            .groupBy { it.membershipKey.medKitId }
            .map { (id, memberships) -> MedKit(id, memberships.map { it.membershipKey.userId }.toSet()) }
            .sortedBy { it.id }

    fun insert(medKit: MedKit) {
        medKits.save(MedKitData(id = medKit.id))
        memberships.saveAll(medKit.members.map { membershipRow(medKit.id, it) })
    }

    /** Сводит строки членства к тому, что в состоянии. Сама аптечка полей больше не имеет. */
    fun save(medKit: MedKit) {
        val stored = memberships.findMemberIds(medKit.id)

        val gone = stored - medKit.members
        if (gone.isNotEmpty()) memberships.deleteMembers(medKit.id, gone)

        val added = medKit.members - stored
        if (added.isNotEmpty()) memberships.saveAll(added.map { membershipRow(medKit.id, it) })
    }

    /**
     * Удаление аптечки.
     *
     * Упаковки уносит каскад из `db/schema.sql`. Членство он унёс бы тоже, но эти строки уже
     * загружены и ссылаются на удаляемую аптечку — Hibernate упал бы на ближайшем flush.
     * Поэтому явно: участников столько, сколько людей ею пользуется, обход дешёвый.
     */
    fun delete(medKit: MedKit) {
        val row = medKits.findByIdOrNull(medKit.id) ?: return
        memberships.deleteAll(memberships.findAllOfMedKit(medKit.id))
        medKits.delete(row)
    }

    /**
     * Строка членства — из одного ключа.
     *
     * Ни аптечка, ни пользователь сюда не поднимаются: обе колонки в ключе, а связи в сущности
     * объявлены только на чтение. Поднимать чужие строки ради ссылки значило бы держать в
     * хранилище членства чужие репозитории и платить за них запросами.
     */
    private fun membershipRow(medKitId: UUID, userId: UUID) = MedKitMembershipData(
        membershipKey = MedKitMembershipKey(medKitId = medKitId, userId = userId)
    )
}
