package org.kert0n.medappserver.testutil

import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.User
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
    private val quantityUnits: QuantityUnitRepository,
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

    // Проверки, существенные для privacy-by-default: `null` означает, что записи больше нет.
    fun drugQuantity(id: UUID): BigDecimal? = drugs.findById(id)?.quantity?.amount
    fun totalPlanned(id: UUID): BigDecimal? = drugs.findById(id)?.plannedTotal?.amount
    fun userPlan(userId: UUID, drugId: UUID): BigDecimal? = drugs.findPlan(userId, drugId)?.plannedAmount?.amount
}
