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
        preparedStatements = preparedStatements - before.preparedStatements,
        queryExecutions = queryExecutions - before.queryExecutions,
        entityFetches = entityFetches - before.entityFetches,
        collectionFetches = collectionFetches - before.collectionFetches
    )
}

data class HibernateMeasurement<T>(
    val result: T,
    val statistics: HibernateStatisticsSnapshot
)

/** Измеряет SQL ORM-сценария через штатную статистику Hibernate. */
@Component
class HibernateStatisticsSupport(
    entityManagerFactory: EntityManagerFactory,
    transactionManager: PlatformTransactionManager,
    private val entityManager: EntityManager
) {
    private val statistics: Statistics =
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val transaction = TransactionTemplate(transactionManager)
    private val measurementLock = ReentrantLock()

    init {
        check(statistics.isStatisticsEnabled) {
            "Для N+1-тестов требуется hibernate.generate_statistics=true"
        }
    }

    fun warmUp(flush: Boolean = false, scenario: () -> Unit) {
        transaction.executeWithoutResult {
            entityManager.clear()
            scenario()
            if (flush) entityManager.flush()
            entityManager.clear()
        }
    }

    fun <T> measure(flush: Boolean = false, scenario: () -> T): HibernateMeasurement<T> {
        check(measurementLock.tryLock()) {
            "Hibernate Statistics нельзя измерять вложенно или параллельно"
        }
        try {
            lateinit var measured: HibernateMeasurement<T>
            val before = statistics.snapshot()
            transaction.executeWithoutResult {
                entityManager.clear()
                val result = scenario()
                if (flush) entityManager.flush()
                entityManager.clear()
                measured = HibernateMeasurement(result, statistics.snapshot() - before)
            }
            return measured
        } finally {
            measurementLock.unlock()
        }
    }

    private fun Statistics.snapshot() = HibernateStatisticsSnapshot(
        preparedStatements = prepareStatementCount,
        queryExecutions = queryExecutionCount,
        entityFetches = entityFetchCount,
        collectionFetches = collectionFetchCount
    )
}
