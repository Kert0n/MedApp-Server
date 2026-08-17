package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.MedKitMembershipData
import org.kert0n.medappserver.db.model.MedKitMembershipKey
import org.kert0n.medappserver.db.repository.MedKitMembershipRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.MedKitOverview
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
    private val memberships: MedKitMembershipRepository,
    private val users: UserRepository
) {

    fun findById(medKitId: UUID): MedKit? {
        val row = medKits.findByIdOrNull(medKitId) ?: return null
        return MedKit(row.id, memberships.findMemberIds(row.id))
    }

    fun findAllOfUser(userId: UUID): List<MedKit> =
        medKits.findAllOfUser(userId).map { MedKit(it.id, memberships.findMemberIds(it.id)) }

    fun overviewsOf(userId: UUID): List<MedKitOverview> = medKits.findOverviewsOfUser(userId)

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
     * Членство и препараты уносит база каскадом по внешнему ключу — тем самым, что описан в
     * `db/schema.sql`. Коллекций для этого в отображении не нужно.
     */
    fun delete(medKitId: UUID) {
        medKits.findByIdOrNull(medKitId)?.let { medKits.delete(it) }
    }

    /**
     * Строка членства.
     *
     * Ссылки берутся ссылками-заглушками: строке нужен только внешний ключ, и читать ради
     * него аптечку с пользователем незачем.
     */
    private fun membershipRow(medKitId: UUID, userId: UUID) = MedKitMembershipData(
        membershipKey = MedKitMembershipKey(medKitId = medKitId, userId = userId),
        medKit = medKits.getReferenceById(medKitId),
        user = users.getReferenceById(userId)
    )
}
