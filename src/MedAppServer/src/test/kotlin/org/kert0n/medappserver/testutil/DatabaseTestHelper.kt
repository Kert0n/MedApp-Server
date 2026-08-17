package org.kert0n.medappserver.testutil

import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.UserStore
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Подготовка данных и точечные проверки состояния.
 *
 * Ходит через хранилища, а не через репозитории: тест обязан пользоваться той же границей,
 * что и приложение, иначе он проверяет не то, что работает в проде.
 */
@Component
class DatabaseTestHelper(
    private val users: UserStore,
    private val drugs: DrugStore,
    private val entityManager: EntityManager
) {
    @Transactional
    fun freshUser(tag: String): User {
        val user = User.register(hashedKey = "${tag}_${UUID.randomUUID()}")
        users.insert(user)
        return user
    }

    @Transactional
    fun freshDrug(medKitId: UUID, quantity: Double): Drug {
        val drug = Drug.create(
            medKitId = medKitId,
            name = "Drug_${UUID.randomUUID()}",
            quantity = qty(quantity),
            quantityUnit = "mg",
            formType = "tablet",
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
    fun drugQuantity(id: UUID): BigDecimal? = drugs.findById(id)?.quantity
    fun totalPlanned(id: UUID): BigDecimal? = drugs.findById(id)?.let { drugs.plannedTotalOf(id) }
    fun userPlan(userId: UUID, drugId: UUID): BigDecimal? = drugs.findPlan(userId, drugId)?.plannedAmount
}
