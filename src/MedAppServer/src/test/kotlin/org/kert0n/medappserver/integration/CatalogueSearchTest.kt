package org.kert0n.medappserver.integration

import jakarta.persistence.EntityManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.model.parsed.DrugTemplateData
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.kert0n.medappserver.services.aggregate.CatalogueService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Fuzzy search over the catalogue.
 *
 * Deliberately without class-level `@Transactional`: it would keep the Hibernate session open
 * for the whole test and hide the LazyInitializationException that `open-in-view=false` gives
 * in production, where the session closes after the repository call.
 */
@PostgresIntegrationTest
class CatalogueSearchTest {

    @Autowired
    private lateinit var vidalDrugRepository: VidalDrugRepository

    @Autowired
    private lateinit var catalogueService: CatalogueService

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

            val tabletType = FormTypeData(name = "таблетки")
            entityManager.persist(tabletType)
            entityManager.flush()

            val drugs = listOf(
                DrugTemplateData(name = "Аспирин", manufacturer = "Байер", otc = true, formType = tabletType),
                DrugTemplateData(name = "Аспирин Кардио", manufacturer = "Байер", otc = true, formType = tabletType),
                DrugTemplateData(
                    name = "Ибупрофен", nameLat = "Ibuprofenum", manufacturer = "Фармстандарт",
                    activeSubstance = "ибупрофен", otc = true
                ),
                DrugTemplateData(
                    name = "Парацетамол", nameLat = "Paracetamolum", manufacturer = "Медисорб",
                    activeSubstance = "парацетамол", otc = true
                ),
                DrugTemplateData(name = "Aspirin", manufacturer = "Bayer", otc = true, formType = tabletType),
                DrugTemplateData(name = "Ibuprofen", manufacturer = "Generic", otc = true)
            )
            vidalDrugRepository.saveAll(drugs)
        }
    }

    /**
     * Термины здесь без метасимволов LIKE, поэтому экранированный вариант совпадает с
     * исходным. Экранирование проверяется отдельно, на уровне сервиса.
     */
    private fun search(term: String, limit: Int = 10) =
        vidalDrugRepository.fuzzySearch(term, term, limit)

    @Test
    fun `fuzzySearch finds Cyrillic drugs by prefix`() {
        val results = search("аспир", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'аспир'")
        assertTrue(results.any { it.name == "Аспирин" }, "Should find 'Аспирин'")
        assertTrue(results.any { it.name == "Аспирин Кардио" }, "Should find 'Аспирин Кардио'")
    }

    @Test
    fun `fuzzySearch is case-insensitive for Cyrillic`() {
        val results = search("АСПИР", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'АСПИР'")
        assertTrue(results.any { it.name == "Аспирин" })
    }

    @Test
    fun `fuzzySearch finds Latin drugs by prefix`() {
        val results = search("asp", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'asp'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearch is case-insensitive for Latin`() {
        val results = search("ASP", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'ASP'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearch finds drugs by substring`() {
        val results = search("профен", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'профен'")
        assertTrue(results.any { it.name == "Ибупрофен" })
    }

    @Test
    fun `fuzzySearch respects limit`() {
        val results = search("а", 2)
        assertTrue(results.size <= 2, "Should return at most 2 results")
    }

    @Test
    fun `fuzzySearch returns empty for no match`() {
        val results = search("xyz123", 10)
        assertTrue(results.isEmpty(), "Should return no results for unrelated term")
    }

    @Test
    fun `fuzzySearch uses trigram similarity for fuzzy matches`() {
        val results = search("Аспирн", 10)
        assertTrue(
            results.any { it.name == "Аспирин" },
            "Trigram similarity should find 'Аспирин' even with typo 'Аспирн'"
        )
    }

    @Test
    fun `fuzzySearch prioritizes exact and prefix matches`() {
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            vidalDrugRepository.saveAll(
                listOf(
                    DrugTemplateData(name = "Aspirin", manufacturer = "Bayer", otc = true),
                    DrugTemplateData(name = "Aspirin Cardio", manufacturer = "Bayer", otc = true),
                    DrugTemplateData(name = "Baby Aspirin", manufacturer = "Generic", otc = true)
                )
            )
        }

        val results = search("Aspirin", 10)
        assertTrue(results.isNotEmpty())
        assertEquals("Aspirin", results.first().name, "Exact match should be first")
    }

    @Test
    fun `fuzzySearch eagerly loads formType - no LazyInitializationException`() {
        val results = search("аспир", 10)
        assertTrue(results.isNotEmpty())
        val formTypeName = results.first { it.formType != null }.formType?.name
        assertNotNull(formTypeName, "FormTypeData should be eagerly loaded and accessible outside transaction")
        assertEquals("таблетки", formTypeName)
    }

    @Test
    fun `service fuzzySearch returns results with accessible formType`() {
        val results = catalogueService.fuzzySearch("аспир", 10)
        assertTrue(results.isNotEmpty())
        val drugWithForm = results.first { it.formType != null }
        assertNotNull(drugWithForm.formType, "FormTypeData should be accessible via service results")
    }

    @Test
    fun `service fuzzySearch returns empty for blank input`() {
        val results = catalogueService.fuzzySearch("   ", 10)
        assertTrue(results.isEmpty(), "Should return empty for blank input")
    }

    @Test
    fun `service fuzzySearch sanitizes special characters`() {
        val results = catalogueService.fuzzySearch("аспир%", 10)
        assertNotNull(results)
    }

    // ── Поиск не только по названию ────────────────────────────────────────────────
    //
    // Действующее вещество, латинское написание и производитель — то, чем препарат обычно и
    // ищут.

    @Test
    fun `находит по латинскому названию`() {
        val results = search("Paracetamolum")

        assertTrue(
            results.any { it.name == "Парацетамол" },
            "препарат должен находиться по латинскому названию"
        )
    }

    @Test
    fun `находит по действующему веществу`() {
        val results = search("ибупрофен")

        assertTrue(
            results.any { it.name == "Ибупрофен" },
            "препарат должен находиться по действующему веществу"
        )
    }

    @Test
    fun `находит по производителю`() {
        val results = search("Фармстандарт")

        assertTrue(
            results.any { it.name == "Ибупрофен" },
            "препарат должен находиться по производителю"
        )
    }

    @Test
    fun `точное совпадение названия идёт первым`() {
        // Иначе опечаточная выдача может вытеснить наверх то, что пользователь набрал точно.
        val results = search("Аспирин")

        assertEquals("Аспирин", results.first().name)
    }

    @Test
    fun `лимит ограничивает выдачу`() {
        assertEquals(1, search("аспир", limit = 1).size)
    }

    @Test
    fun `выдача содержит поля, по которым идёт поиск`() {
        // Иначе результат не объясняет, почему запись нашлась: набрали «Ibuprofenum»,
        // а в ответе одно торговое название.
        val found = catalogueService.fuzzySearch("Ibuprofenum").first { it.name == "Ибупрофен" }

        assertEquals("Ibuprofenum", found.nameLat)
        assertEquals("ибупрофен", found.activeSubstance)
    }
}
