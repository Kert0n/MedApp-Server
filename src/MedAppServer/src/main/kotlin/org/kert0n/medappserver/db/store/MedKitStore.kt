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
import org.kert0n.medappserver.domain.MedKitRef
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
        return MedKit(row.id, memberships.findMemberIds(row.id), row.version)
    }

    /** Аптечки участника без состава: идентификатор и версия — всё, что нужно снимку. */
    fun findRefsOfUser(userId: UUID): List<MedKitRef> = medKits.findRefsOfUser(userId)

    fun overviewsOf(userId: UUID): List<MedKitOverview> = medKits.findOverviewsOfUser(userId)

    fun insert(medKit: MedKit) {
        val row = medKits.save(MedKitData(id = medKit.id))
        memberships.saveAll(medKit.members.map { membershipRow(row, it) })
    }

    /**
     * Сводит строки членства к тому, что в состоянии. Сама аптечка полей больше не имеет.
     *
     * Отсюда и явное присваивание версии: измениться строке `med_kits` нечем, состав живёт в
     * другой таблице, и dirty checking о нём не знает. Без продвижения версии двум командам
     * членства ничто не мешало бы разойтись, каждой по своему прочитанному составу, — а это и
     * есть тот случай, ради которого версия у аптечки заведена: два последних участника,
     * выходящих одновременно, оба решают «я не последний».
     *
     * `OPTIMISTIC_FORCE_INCREMENT` сюда не годится по двум измеренным причинам: он применяется
     * только перед коммитом, поэтому новую версию нечем вернуть в ответе, и складывается с
     * обычным инкрементом, давая два шага вместо одного.
     */
    fun save(medKit: MedKit): MedKit {
        val row = medKits.findByIdOrNull(medKit.id) ?: error("Аптечка ${medKit.id} исчезла во время записи")
        val stored = memberships.findMemberIds(medKit.id)

        val gone = stored - medKit.members
        if (gone.isNotEmpty()) memberships.deleteMembers(medKit.id, gone)

        val added = medKit.members - stored
        if (added.isNotEmpty()) {
            memberships.saveAll(added.map { membershipRow(row, it) })
        }

        if (gone.isNotEmpty() || added.isNotEmpty()) {
            row.version = row.version + 1
        }

        medKits.flush()
        return medKit.copy(version = row.version)
    }

    /**
     * Удаление аптечки.
     *
     * Препараты уносит база каскадом по внешнему ключу — тем самым, что описан в
     * `db/schema.sql`. Членство пришлось бы унести тем же каскадом, но строки членства к
     * этому моменту уже загружены и ссылаются на удаляемую аптечку: Hibernate увидел бы
     * ссылку на исчезнувшую запись и упал бы на ближайшем flush. Поэтому они удаляются явно,
     * а участников у аптечки столько, сколько людей ею пользуется, — обход дешёвый.
     *
     * Форсировать версию тут нечем и незачем: удаление и так идёт с предикатом по ней, и
     * вступление, случившееся между чтением и удалением, отменит эту команду.
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
