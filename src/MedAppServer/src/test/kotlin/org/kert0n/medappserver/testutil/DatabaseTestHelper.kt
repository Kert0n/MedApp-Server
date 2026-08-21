package org.kert0n.medappserver.testutil

import java.math.BigDecimal
import java.util.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.eq
import org.kert0n.medappserver.db.tables.Drugs
import org.kert0n.medappserver.db.tables.MedKitMemberships
import org.kert0n.medappserver.db.tables.QuantityUnits
import org.kert0n.medappserver.db.tables.Reservations
import org.kert0n.medappserver.db.store.DrugStore
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
    private val reservations: ReservationStore,
    private val medKits: MedKitStore,
    private val medKitService: MedKitService,
    private val drugService: DrugService,
    private val reservationService: ReservationService
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
        val stored = QuantityUnits.selectAll().where { QuantityUnits.name eq name }.singleOrNull()
        if (stored != null) return QuantityUnit(stored[QuantityUnits.id], stored[QuantityUnits.name])

        val id = UUID.randomUUID()
        QuantityUnits.insert { it[QuantityUnits.id] = id; it[QuantityUnits.name] = name }
        return QuantityUnit(id, name)
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

    /**
     * Пусто и останется пустым.
     *
     * При Hibernate тут синхронизировали persistence context: без этого чтение отдавало
     * загруженную копию вместо строки из базы. В Exposed синхронизировать нечего — запрос идёт
     * в базу всегда. Метод оставлен, чтобы не переписывать сотню вызовов ради ничего.
     */
    fun flushAndClear() = Unit

    /**
     * Препарат без проверки доступа.
     *
     * В приложении такого чтения нет — там доступ проверяется всегда; тесту же нужно
     * убедиться, что строки не стало, безотносительно того, кто спрашивает.
     */
    /** Упаковка без оглядки на доступ — под проверки состояния, как и `medKit`. */
    @Transactional
    fun drug(id: UUID): Drug? =
        Drugs.selectAll().where { Drugs.id eq id }.singleOrNull()?.let { drugs.find(id, it[Drugs.medKitId].let { _ ->
            MedKitMemberships.selectAll().where { MedKitMemberships.medKitId eq it[Drugs.medKitId] }
                .first()[MedKitMemberships.userId]
        }) }

    /**
     * Аптечка без оглядки на доступ — под проверки состояния.
     *
     * Единственное место, где чтение идёт мимо хранилища: скоупленное чтение приложения по
     * замыслу не покажет ни удалённую аптечку, ни ту, из которой участник вышел, а проверять
     * надо именно это. Подготовка сценария по-прежнему идёт через хранилища.
     */
    @Transactional
    fun medKit(medKitId: UUID): MedKit? {
        val members = MedKitMemberships.selectAll()
            .where { MedKitMemberships.medKitId eq medKitId }
            .map { it[MedKitMemberships.userId] }
            .toSet()
        return if (members.isEmpty()) null else MedKit(medKitId, members)
    }

    @Transactional
    fun requireDrug(id: UUID): Drug = drug(id) ?: error("Препарат $id не найден")

    // Проверки, существенные для privacy-by-default: `null` означает, что записи больше нет.
    @Transactional
    fun drugQuantity(id: UUID): BigDecimal? = drug(id)?.quantity?.amount

    /**
     * Заявленное бронями: считается снаружи упаковки и может превышать её остаток.
     *
     * Мимо хранилища, как и `medKit`: у проверки состояния нет вызывающего, а скоупленное
     * чтение без него не работает.
     */
    @Transactional
    fun reservedOnDrug(id: UUID): BigDecimal =
        Reservations.selectAll().where { Reservations.drugId eq id }.sumOf { it[Reservations.amount] }

    @Transactional
    fun userReservation(userId: UUID, drugId: UUID): BigDecimal? =
        reservations.find(userId, drugId)?.amount?.amount
}
