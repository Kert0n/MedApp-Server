package org.kert0n.medappserver.db.store

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.util.UUID
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.MedKitMembershipData
import org.kert0n.medappserver.db.model.MedKitMembershipKey
import org.kert0n.medappserver.db.repository.MedKitMembershipRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.hibernate.exception.ConstraintViolationException
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.StaleAggregateVersion
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

    fun findById(medKitId: UUID): MedKit? {
        val row = medKits.findByIdOrNull(medKitId) ?: return null
        return MedKit(row.id, memberships.findMemberIds(row.id), row.version)
    }

    /**
     * Все аптечки участника — агрегатами и одним запросом.
     *
     * Строки членства приходят со своими аптечками, состав собирается группировкой в памяти.
     */
    fun findAllOfUser(userId: UUID): List<MedKit> =
        medKits.findMembershipsOfUserKits(userId)
            .groupBy { it.medKit }
            .map { (row, rows) -> MedKit(row.id, rows.map { it.membershipKey.userId }.toSet(), row.version) }
            .sortedBy { it.id }

    /**
     * Требует, чтобы состав аптечки не изменился до конца транзакции.
     *
     * Нужно тому, кто **решает по составу, а сам аптечку не меняет**: перенос упаковки смотрит,
     * кто её увидит после переезда, и убирает брони остальных. Если в этот момент кто-то выйдет,
     * решение окажется принятым по составу, которого уже нет, и бронь вышедшего переживёт утрату
     * доступа.
     *
     * `OPTIMISTIC` — это проверка версии на коммите, без её продвижения: чтение не команда и
     * чужой токен обесценивать не должно.
     */
    fun requireUnchanged(medKit: MedKit) {
        // Сравнение явное, а не «обе величины из одной транзакции, значит совпадут»: снимок
        // может приехать и из другого чтения, и тогда молчаливое совпадение — везение.
        val row = medKits.findByIdOrNull(medKit.id) ?: throw StaleAggregateVersion()
        if (row.version != medKit.version) throw StaleAggregateVersion()
        entityManager.lock(row, LockModeType.OPTIMISTIC)
    }

    fun insert(medKit: MedKit) {
        val row = medKits.save(MedKitData(id = medKit.id))
        memberships.saveAll(medKit.members.map { membershipRow(row, it) })
    }

    /**
     * Сводит строки членства к тому, что в состоянии, и двигает версию аптечки.
     *
     * Собственных полей у аптечки нет, её строка не меняется — dirty checking версию не тронет,
     * и потерянное обновление состава остался бы незамеченным. Поэтому заявка на изменение
     * корня: `OPTIMISTIC_FORCE_INCREMENT` — это то самое «меняется агрегат, а не строка».
     *
     * Замерено: заявка применяется перед коммитом и складывается с инкрементом от dirty
     * checking, если строка всё-таки менялась. Скачок версии больше чем на единицу законен —
     * версия это токен состояния, а не счётчик команд.
     */
    fun save(medKit: MedKit) {
        val row = medKits.findByIdOrNull(medKit.id) ?: error("Аптечка ${medKit.id} исчезла во время записи")
        entityManager.lock(row, LockModeType.OPTIMISTIC_FORCE_INCREMENT)

        val stored = memberships.findMemberIds(medKit.id)

        val gone = stored - medKit.members
        if (gone.isNotEmpty()) memberships.deleteMembers(medKit.id, gone)

        val added = medKit.members - stored
        if (added.isNotEmpty()) {
            memberships.saveAll(added.map { membershipRow(row, it) })
            // Аптечка могла исчезнуть, пока вступающий смотрел на её состав: внешний ключ это
            // поймает, но на коммите — там уже некому сказать, что аптечки просто нет.
            flushTranslating(MEMBERSHIP_KIT_FK) { NotAMember() }
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
    /**
     * Флашит и переводит нарушение **названного** ограничения в доменный отказ.
     *
     * По имени, а не по типу исключения: перевод любого нарушения целостности в отказ клиенту
     * спрятал бы настоящую поломку схемы. Незнакомое ограничение остаётся серверной ошибкой.
     */
    private fun flushTranslating(constraint: String, refusal: () -> DomainRuleViolated) {
        try {
            entityManager.flush()
        } catch (violation: ConstraintViolationException) {
            if (violation.constraintName?.lowercase() == constraint) throw refusal()
            throw violation
        }
    }

    private fun membershipRow(medKit: MedKitData, userId: UUID) = MedKitMembershipData(
        membershipKey = MedKitMembershipKey(medKitId = medKit.id, userId = userId),
        medKit = medKit,
        user = users.findByIdOrNull(userId) ?: error("Пользователь $userId исчез во время записи членства")
    )

    private companion object {
        const val MEMBERSHIP_KIT_FK = "user_med_kits_med_kit_fkey"
    }
}
