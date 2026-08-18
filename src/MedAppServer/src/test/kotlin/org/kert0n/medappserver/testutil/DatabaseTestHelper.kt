package org.kert0n.medappserver.testutil

import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.domain.User
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
    private val quantityUnits: QuantityUnitRepository,
    private val medKitService: MedKitService,
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

    /** Вступление без приглашения: тесту нужен состав, а не проверка ключа. */
    @Transactional
    fun join(medKitId: UUID, userId: UUID): MedKit = medKitService.join(medKitId, userId)

    /** Бронь под подготовку сценария. */
    @Transactional
    fun reserve(userId: UUID, drugId: UUID, amount: BigDecimal): Reservation =
        reservationService.create(userId, drugId, amount)

    /** Кладёт заранее собранный препарат: тестам нужны свои имена и количества. */
    @Transactional
    fun insert(drug: Drug): Drug {
        drugs.insert(drug)
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
    fun drug(id: UUID): Drug? = drugs.findById(id)

    fun requireDrug(id: UUID): Drug = drug(id) ?: error("Препарат $id не найден")

    // Проверки, существенные для privacy-by-default: `null` означает, что записи больше нет.
    fun drugQuantity(id: UUID): BigDecimal? = drugs.findById(id)?.quantity?.amount

    /** Заявленное бронями: считается снаружи упаковки и может превышать её остаток. */
    fun reservedOnDrug(id: UUID): BigDecimal =
        reservations.findAllOfDrugs(listOf(id)).sumOf { it.amount.amount }

    fun userReservation(userId: UUID, drugId: UUID): BigDecimal? =
        reservations.find(userId, drugId)?.amount?.amount

    /**
     * Текущие версии — чтобы команда предъявила ту, по которой «решал клиент».
     *
     * В тестах, где проверяется не предусловие, а сама операция, читать версию перед вызовом
     * честнее, чем помнить её от предыдущей строки: любая команда двигает её на неизвестную
     * величину, и держать это в голове по всему тесту значит проверять свою память.
     */
    fun drugVersion(drugId: UUID): Long = drugs.findById(drugId)?.version ?: error("Упаковки $drugId нет")

    fun reservationVersion(userId: UUID, drugId: UUID): Long =
        reservationVersionOrNull(userId, drugId) ?: error("Брони $userId/$drugId нет")

    /** `null` — брони ещё нет, и предъявлять её версию нечем. */
    fun reservationVersionOrNull(userId: UUID, drugId: UUID): Long? = reservations.find(userId, drugId)?.version

    fun medKitVersion(medKitId: UUID): Long = medKits.findById(medKitId)?.version ?: error("Аптечки $medKitId нет")
}
