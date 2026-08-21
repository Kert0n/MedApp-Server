package org.kert0n.medappserver.testutil

import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitMembershipRepository
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.repository.ReservationRepository
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.toDomain
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Подготовка данных и точечные проверки состояния.
 *
 * Ходит через хранилища, а не через репозитории: тест обязан пользоваться той же границей,
 * что и приложение. Исключение — словарь единиц измерения: его наполняет импорт каталога, а
 * тесту нужна хотя бы одна запись, чтобы препарат было в чём измерять.
 */
@Component
class DatabaseTestHelper(
    private val users: UserStore,
    private val drugs: DrugStore,
    private val drugRows: DrugRepository,
    private val reservations: ReservationStore,
    private val reservationRows: ReservationRepository,
    private val medKits: MedKitStore,
    private val memberships: MedKitMembershipRepository,
    private val quantityUnits: QuantityUnitRepository,
    private val medKitService: MedKitService,
    private val drugService: DrugService,
    private val reservationService: ReservationService,
    private val entityManager: EntityManager
) {
    @Transactional
    fun freshUser(tag: String): User {
        val user = User(hashedKey = "${tag}_${UUID.randomUUID()}")
        users.insert(user)
        return user
    }

    /** Единица измерения из словаря; заводится один раз и переиспользуется. */
    @Transactional
    fun unit(name: String = "mg"): QuantityUnit {
        val stored = quantityUnits.findAll().find { it.name == name }
            ?: quantityUnits.save(QuantityUnitData(name = name))
        return QuantityUnit(stored.id, stored.name)
    }

    @Transactional
    fun freshDrug(medKitId: UUID, quantity: Double): Drug {
        val drug = Drug(
            medKitId = medKitId,
            name = "Drug_${UUID.randomUUID()}",
            quantity = Quantity(qty(quantity), unit()),
            category = "painkiller",
            manufacturer = "Test Pharma",
            country = "TestLand",
            description = "Test description"
        )
        drugs.insert(drug)
        flushAndClear()
        return drug
    }

    /**
     * Аптечка с одним участником — под подготовку сценария.
     *
     * Подготовка идёт отсюда, а не прямым вызовом сервиса агрегата: агрегат транзакцию не
     * открывает, а этот помощник — открывает. Действие, которое тест **проверяет**, зовётся
     * через фасад: тем же входом, что и приложение.
     */
    @Transactional
    fun freshMedKit(ownerId: UUID): MedKit = medKitService.create(ownerId)

    /**
     * Вступление под подготовку сценария — тем же путём, что и приложение.
     *
     * Вступить иначе нельзя: приглашение и есть право прочитать аптечку, в которой тебя ещё
     * нет. Ключ выписывается от имени участника, который в ней уже состоит.
     */
    @Transactional
    fun join(medKitId: UUID, invitedBy: UUID, userId: UUID): MedKit =
        medKitService.joinByInvitation(medKitService.invite(medKitService.get(medKitId, invitedBy), invitedBy), userId)

    /** Бронь под подготовку сценария. */
    @Transactional
    fun reserve(userId: UUID, drugId: UUID, amount: BigDecimal): Reservation =
        reservationService.create(drugService.get(drugId, userId), userId, amount)
            .also { flushAndClear() }

    /** Кладёт заранее собранный препарат: тестам нужны свои имена и количества. */
    /**
     * Кладёт заранее собранный препарат: тестам нужны свои имена и количества.
     *
     * После записи контекст очищается: связи со словарями объявлены только на чтение, и у
     * свежей строки они пусты — следующее чтение должно сходить в базу, а не получить
     * полусобранную копию. В проде такого не бывает: там команда отдаёт доменный объект, а не
     * перечитывает только что записанное.
     */
    @Transactional
    fun insert(drug: Drug): Drug {
        drugs.insert(drug)
        flushAndClear()
        return drug
    }

    /** Кладёт заранее собранного пользователя. */
    @Transactional
    fun insert(user: User): User {
        users.insert(user)
        return user
    }

    fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    /**
     * Препарат без проверки доступа.
     *
     * В приложении такого чтения нет — там доступ проверяется всегда; тесту же нужно
     * убедиться, что строки не стало, безотносительно того, кто спрашивает.
     */
    /** Упаковка без оглядки на доступ — под проверки состояния, как и `medKit`. */
    fun drug(id: UUID): Drug? = drugRows.findFullById(id)?.toDomain()

    /**
     * Аптечка без оглядки на доступ — под проверки состояния.
     *
     * Единственное место, где чтение идёт мимо хранилища: скоупленное чтение приложения по
     * замыслу не покажет ни удалённую аптечку, ни ту, из которой участник вышел, а проверять
     * надо именно это. Подготовка сценария по-прежнему идёт через хранилища.
     */
    fun medKit(medKitId: UUID): MedKit? {
        val members = memberships.findMemberIds(medKitId)
        return if (members.isEmpty()) null else MedKit(medKitId, members)
    }

    fun requireDrug(id: UUID): Drug = drug(id) ?: error("Препарат $id не найден")

    // Проверки, существенные для privacy-by-default: `null` означает, что записи больше нет.
    fun drugQuantity(id: UUID): BigDecimal? = drug(id)?.quantity?.amount

    /**
     * Заявленное бронями: считается снаружи упаковки и может превышать её остаток.
     *
     * Мимо хранилища, как и `medKit`: у проверки состояния нет вызывающего, а скоупленное
     * чтение без него не работает.
     */
    fun reservedOnDrug(id: UUID): BigDecimal =
        reservationRows.findAll().filter { it.reservationKey.drugId == id }.sumOf { it.amount }

    fun userReservation(userId: UUID, drugId: UUID): BigDecimal? =
        reservations.find(userId, drugId)?.amount?.amount
}
