package org.kert0n.medappserver.db.store

import jakarta.persistence.EntityManager
import java.util.UUID
import org.hibernate.exception.ConstraintViolationException
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.MedKitMembershipData
import org.kert0n.medappserver.db.model.MedKitMembershipKey
import org.kert0n.medappserver.db.repository.MedKitMembershipRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
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
    private val users: UserRepository,
    private val entityManager: EntityManager
) {

    /**
     * Аптечка, доступная вызывающему, — одним запросом.
     *
     * Доступ проверяет сам запрос: не нашли — значит либо нет аптечки, либо вызывающего нет в
     * ней, и различать эти случаи мы не собираемся. Состав приходит целиком: агрегат аптечки и
     * есть её состав, а решения по нему принимает не только тот, кто её читал.
     */
    fun findAccessible(medKitId: UUID, userId: UUID): MedKit? {
        val rows = memberships.findAccessibleMemberships(medKitId, userId)
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
            .groupBy { it.medKit.id }
            .map { (id, memberships) -> MedKit(id, memberships.map { it.membershipKey.userId }.toSet()) }
            .sortedBy { it.id }

    fun insert(medKit: MedKit) {
        val row = medKits.save(MedKitData(id = medKit.id))
        memberships.saveAll(medKit.members.map { membershipRow(row, it) })
    }

    /**
     * Вставляет строку членства, не читая аптечку.
     *
     * Обслуживает вступление по приглашению: вызывающего в аптечке ещё нет, и скоупленное
     * чтение его туда не пустит. Несуществующая аптечка и повторное вступление приезжают
     * нарушениями именованных ключей — читать перед записью значило бы всё равно проиграть
     * гонку и заплатить лишним запросом.
     */
    fun isMember(medKitId: UUID, userId: UUID): Boolean =
        memberships.existsById(MedKitMembershipKey(medKitId = medKitId, userId = userId))

    fun addMember(medKitId: UUID, userId: UUID) {
        val medKit = medKits.findByIdOrNull(medKitId) ?: throw NotAMember()
        // persist, а не save: ключ присвоенный, и save пошёл бы через merge — повторное
        // вступление стало бы обновлением существующей строки вместо нарушения ключа.
        entityManager.persist(membershipRow(medKit, userId))
        try {
            entityManager.flush()
        } catch (violation: ConstraintViolationException) {
            when (violation.constraintName?.lowercase()) {
                MEMBERSHIP_KEY -> throw AlreadyMember()
                MEMBERSHIP_MED_KIT_KEY -> throw NotAMember()
                else -> throw violation
            }
        }
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
     * Упаковки уносит каскад из `db/schema.sql`. Членство он унёс бы тоже, но эти строки уже
     * загружены и ссылаются на удаляемую аптечку — Hibernate упал бы на ближайшем flush.
     * Поэтому явно: участников столько, сколько людей ею пользуется, обход дешёвый.
     */
    fun delete(medKitId: UUID) {
        val row = medKits.findByIdOrNull(medKitId) ?: return
        memberships.deleteAll(memberships.findAllOfMedKit(medKitId))
        medKits.delete(row)
    }

    /**
     * Строка членства.
     *
     * Ссылки — управляемые сущности, а не заглушки `getReferenceById`: заглушка на запись, ещё
     * не дошедшую до базы, при первом массовом запросе превращается в «ссылку на несохранённый
     * объект». Запросов это не стоит: обе сущности уже в контексте.
     */
    private companion object {
        const val MEMBERSHIP_KEY = "user_med_kits_pkey"
        const val MEMBERSHIP_MED_KIT_KEY = "user_med_kits_med_kit_fkey"
    }

    private fun membershipRow(medKit: MedKitData, userId: UUID) = MedKitMembershipData(
        membershipKey = MedKitMembershipKey(medKitId = medKit.id, userId = userId),
        medKit = medKit,
        user = users.findByIdOrNull(userId) ?: error("Пользователь $userId исчез во время записи членства")
    )
}
