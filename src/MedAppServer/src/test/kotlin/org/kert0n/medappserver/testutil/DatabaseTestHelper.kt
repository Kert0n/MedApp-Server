package org.kert0n.medappserver.testutil

import java.math.BigDecimal

import jakarta.persistence.EntityManager
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Component
class DatabaseTestHelper(
    private val userRepository: UserRepository,
    private val drugRepository: DrugRepository,
    private val usingRepository: UsingRepository,
    private val entityManager: EntityManager
) {
    @Transactional
    fun freshUser(tag: String): User {
        val user = userBuilder().withHashedKey("${tag}_${UUID.randomUUID()}").build()
        return userRepository.save(user)
    }

    @Transactional
    fun freshDrug(medKit: MedKit, quantity: Double): Drug {
        val drug = drugBuilder(medKit)
            .withName("Drug_${UUID.randomUUID()}")
            .withQuantity(quantity)
            .build()
        return drugRepository.save(drug)
    }

    fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    // Null-safe getters essential for testing Privacy-by-Default (deletion)
    fun drugQuantity(id: UUID): BigDecimal? = drugRepository.findByIdOrNull(id)?.quantity
    fun totalPlanned(id: UUID): BigDecimal? = drugRepository.findByIdOrNull(id)?.totalPlannedAmount
    fun userPlan(userId: UUID, drugId: UUID): BigDecimal? =
        usingRepository.findByUserIdAndDrugId(userId, drugId)?.plannedAmount
}