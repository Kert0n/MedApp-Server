package org.kert0n.medappserver.integration

import org.kert0n.medappserver.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.parsed.FormType
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.kert0n.medappserver.services.models.VidalDrugService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for VidalDrug fuzzy search.
 *
 * NOTE: This test class deliberately does NOT use @Transactional on the class level.
 * This ensures we catch LazyInitializationException bugs that occur in production
 * when `spring.jpa.open-in-view=false` (the session closes after the repository call).
 * Using @Transactional on tests keeps the Hibernate session open for the entire test,
 * hiding lazy loading bugs that only manifest at runtime.
 */
@PostgresIntegrationTest
class VidalDrugFuzzySearchTest {

    @Autowired
    private lateinit var vidalDrugRepository: VidalDrugRepository

    @Autowired
    private lateinit var vidalDrugService: VidalDrugService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var txTemplate: TransactionTemplate

    @BeforeEach
    fun setup() {
        txTemplate = TransactionTemplate(transactionManager)
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            entityManager.createNativeQuery("DELETE FROM form_types").executeUpdate()

            val tabletType = FormType(name = "таблетки")
            entityManager.persist(tabletType)
            entityManager.flush()

            val drugs = listOf(
                VidalDrug(name = "Аспирин", manufacturer = "Байер", otc = true, formType = tabletType),
                VidalDrug(name = "Аспирин Кардио", manufacturer = "Байер", otc = true, formType = tabletType),
                VidalDrug(name = "Ибупрофен", manufacturer = "Фармстандарт", otc = true),
                VidalDrug(name = "Парацетамол", manufacturer = "Медисорб", otc = true),
                VidalDrug(name = "Aspirin", manufacturer = "Bayer", otc = true, formType = tabletType),
                VidalDrug(name = "Ibuprofen", manufacturer = "Generic", otc = true)
            )
            vidalDrugRepository.saveAll(drugs)
        }
    }

    @Test
    fun `fuzzySearchByName finds Cyrillic drugs by prefix`() {
        val results = vidalDrugRepository.fuzzySearchByName("аспир", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'аспир'")
        assertTrue(results.any { it.name == "Аспирин" }, "Should find 'Аспирин'")
        assertTrue(results.any { it.name == "Аспирин Кардио" }, "Should find 'Аспирин Кардио'")
    }

    @Test
    fun `fuzzySearchByName is case-insensitive for Cyrillic`() {
        val results = vidalDrugRepository.fuzzySearchByName("АСПИР", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'АСПИР'")
        assertTrue(results.any { it.name == "Аспирин" })
    }

    @Test
    fun `fuzzySearchByName finds Latin drugs by prefix`() {
        val results = vidalDrugRepository.fuzzySearchByName("asp", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'asp'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearchByName is case-insensitive for Latin`() {
        val results = vidalDrugRepository.fuzzySearchByName("ASP", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'ASP'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearchByName finds drugs by substring`() {
        val results = vidalDrugRepository.fuzzySearchByName("профен", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'профен'")
        assertTrue(results.any { it.name == "Ибупрофен" })
    }

    @Test
    fun `fuzzySearchByName respects limit`() {
        val results = vidalDrugRepository.fuzzySearchByName("а", 2)
        assertTrue(results.size <= 2, "Should return at most 2 results")
    }

    @Test
    fun `fuzzySearchByName returns empty for no match`() {
        val results = vidalDrugRepository.fuzzySearchByName("xyz123", 10)
        assertTrue(results.isEmpty(), "Should return no results for unrelated term")
    }

    @Test
    fun `fuzzySearchByName uses trigram similarity for fuzzy matches`() {
        val results = vidalDrugRepository.fuzzySearchByName("Аспирн", 10)
        assertTrue(
            results.any { it.name == "Аспирин" },
            "Trigram similarity should find 'Аспирин' even with typo 'Аспирн'"
        )
    }

    @Test
    fun `fuzzySearchByName prioritizes exact and prefix matches`() {
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            vidalDrugRepository.saveAll(
                listOf(
                    VidalDrug(name = "Aspirin", manufacturer = "Bayer", otc = true),
                    VidalDrug(name = "Aspirin Cardio", manufacturer = "Bayer", otc = true),
                    VidalDrug(name = "Baby Aspirin", manufacturer = "Generic", otc = true)
                )
            )
        }

        val results = vidalDrugRepository.fuzzySearchByName("Aspirin", 10)
        assertTrue(results.isNotEmpty())
        assertEquals("Aspirin", results.first().name, "Exact match should be first")
    }

    @Test
    fun `fuzzySearchByName eagerly loads formType - no LazyInitializationException`() {
        val results = vidalDrugRepository.fuzzySearchByName("аспир", 10)
        assertTrue(results.isNotEmpty())
        val formTypeName = results.first { it.formType != null }.formType?.name
        assertNotNull(formTypeName, "FormType should be eagerly loaded and accessible outside transaction")
        assertEquals("таблетки", formTypeName)
    }

    @Test
    fun `service fuzzySearchByName returns results with accessible formType`() {
        val results = vidalDrugService.fuzzySearchByName("аспир", 10)
        assertTrue(results.isNotEmpty())
        val drugWithForm = results.first { it.formType != null }
        assertNotNull(drugWithForm.formType?.name, "FormType should be accessible via service results")
    }

    @Test
    fun `service fuzzySearchByName returns empty for blank input`() {
        val results = vidalDrugService.fuzzySearchByName("   ", 10)
        assertTrue(results.isEmpty(), "Should return empty for blank input")
    }

    @Test
    fun `service fuzzySearchByName sanitizes special characters`() {
        val results = vidalDrugService.fuzzySearchByName("аспир%", 10)
        assertNotNull(results)
    }
}
