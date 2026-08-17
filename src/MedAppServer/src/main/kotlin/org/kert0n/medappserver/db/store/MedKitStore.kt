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

    /** Идентификаторы аптечек участника: их состав вызывающему не нужен. */
    fun findIdsOfUser(userId: UUID): List<UUID> = medKits.findIdsOfUser(userId)

    fun overviewsOf(userId: UUID): List<MedKitOverview> = medKits.findOverviewsOfUser(userId)

    fun insert(medKit: MedKit) {
        val row = medKits.save(MedKitData(id = medKit.id))
        memberships.saveAll(medKit.members.map { membershipRow(row, it) })
    }

    /** Сводит строки членства к тому, что в состоянии. Сама аптечка полей больше не имеет. */
    fun save(medKit: MedKit) {
        val stored = memberships.findMemberIds(medKit.id)

        val gone = stored - medKit.members
        if (gone.isNotEmpty()) memberships.deleteMembers(medKit.id, gone)

        val added = medKit.members - stored
        if (added.isNotEmpty()) {
            val row = medKits.findByIdOrNull(medKit.id) ?: error("Аптечка ${medKit.id} исчезла во время записи")
            memberships.saveAll(added.map { membershipRow(row, it) })
        }
    }

    /**
     * Удаление аптечки.
     *
     * Препараты уносит база каскадом по внешнему ключу — тем самым, что описан в
     * `db/schema.sql`. Членство пришлось бы унести тем же каскадом, но строки членства к
     * этому моменту уже загружены и ссылаются на удаляемую аптечку: Hibernate увидел бы
     * ссылку на исчезнувшую запись и упал бы на ближайшем flush. Поэтому они удаляются явно,
     * а участников у аптечки столько, сколько людей ею пользуется, — обход дешёвый.
     */
    fun delete(medKitId: UUID) {
        val row = medKits.findByIdOrNull(medKitId) ?: return
        memberships.deleteAll(memberships.findAllOfMedKit(medKitId))
        medKits.delete(row)
    }

    /**
     * Строка членства.
     *
     * Ссылки берутся управляемыми сущностями, а не заглушками `getReferenceById`: заглушка на
     * запись, ещё не дошедшую до базы, при первом же массовом запросе превращается в
     * «ссылку на несохранённый объект» — Hibernate флашит контекст перед DML и видит прокси
     * без строки за ним. Лишних запросов это не стоит: обе сущности уже в контексте.
     */
    private fun membershipRow(medKit: MedKitData, userId: UUID) = MedKitMembershipData(
        membershipKey = MedKitMembershipKey(medKitId = medKit.id, userId = userId),
        medKit = medKit,
        user = users.findByIdOrNull(userId) ?: error("Пользователь $userId исчез во время записи членства")
    )
}
