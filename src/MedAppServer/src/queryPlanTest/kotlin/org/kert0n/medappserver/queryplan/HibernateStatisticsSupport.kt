package org.kert0n.medappserver.queryplan

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.locks.ReentrantLock

data class HibernateStatisticsSnapshot(
    val preparedStatements: Long,
    val queryExecutions: Long,
    val entityFetches: Long,
    val collectionFetches: Long
) {
    operator fun minus(before: HibernateStatisticsSnapshot) = HibernateStatisticsSnapshot(
        preparedStatements - before.preparedStatements,
        queryExecutions - before.queryExecutions,
        entityFetches - before.entityFetches,
        collectionFetches - before.collectionFetches
    )
}

data class HibernateMeasurement<T>(val result: T, val statistics: HibernateStatisticsSnapshot)

/** Measures ORM work only. Direct JDBC is measured at the DataSource boundary. */
@Component
class HibernateStatisticsSupport(
    entityManagerFactory: EntityManagerFactory,
    transactionManager: PlatformTransactionManager,
    private val entityManager: EntityManager
) {
    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val transaction = TransactionTemplate(transactionManager)
    private val lock = ReentrantLock()

    init {
        check(statistics.isStatisticsEnabled) { "hibernate.generate_statistics=true is required" }
    }

    fun warmUp(scenario: () -> Unit) {
        transaction.executeWithoutResult {
            entityManager.clear()
            scenario()
            entityManager.clear()
        }
    }

    fun <T> measure(scenario: () -> T): HibernateMeasurement<T> {
        check(lock.tryLock()) { "Hibernate Statistics measurement cannot be nested or concurrent" }
        try {
            lateinit var measurement: HibernateMeasurement<T>
            val before = statistics.snapshot()
            transaction.executeWithoutResult {
                entityManager.clear()
                measurement = HibernateMeasurement(scenario(), statistics.snapshot() - before)
                entityManager.clear()
            }
            return measurement
        } finally {
            lock.unlock()
        }
    }

    private fun Statistics.snapshot() = HibernateStatisticsSnapshot(
        prepareStatementCount,
        queryExecutionCount,
        entityFetchCount,
        collectionFetchCount
    )
}
