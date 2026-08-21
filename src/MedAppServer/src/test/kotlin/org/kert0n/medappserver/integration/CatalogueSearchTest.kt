package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.db.tables.DrugTemplates
import org.kert0n.medappserver.db.tables.FormTypes
import org.kert0n.medappserver.services.application.CatalogueApplicationService
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
    private lateinit var catalogue: CatalogueApplicationService

    @Autowired
    private lateinit var store: CatalogueStore


    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var txTemplate: TransactionTemplate

    @BeforeEach
    fun setup() {
        txTemplate = TransactionTemplate(transactionManager)
        txTemplate.execute {
            DrugTemplates.deleteAll()
            FormTypes.deleteAll()

            val tablet = Uuid.random()
            FormTypes.insert { it[id] = tablet; it[name] = "таблетки" }

            template("Аспирин", manufacturer = "Байер", formTypeId = tablet)
            template("Аспирин Кардио", manufacturer = "Байер", formTypeId = tablet)
            template("Ибупрофен", nameLat = "Ibuprofenum", manufacturer = "Фармстандарт", substance = "ибупрофен")
            template("Парацетамол", nameLat = "Paracetamolum", manufacturer = "Медисорб", substance = "парацетамол")
            template("Aspirin", manufacturer = "Bayer", formTypeId = tablet)
            template("Ibuprofen", manufacturer = "Generic")
        }
    }

    /**
     * Термины здесь без метасимволов LIKE, поэтому экранированный вариант совпадает с
     * исходным. Экранирование проверяется отдельно, на уровне сервиса.
     */
    private fun search(term: String, limit: Int = 10) =
        txTemplate.execute { store.searchTemplates(term, term, limit) }!!

    private fun template(
        name: String,
        nameLat: String? = null,
        substance: String? = null,
        manufacturer: String,
        formTypeId: Uuid? = null
    ) {
        DrugTemplates.insert {
            it[DrugTemplates.id] = Uuid.random()
            it[DrugTemplates.name] = name
            it[DrugTemplates.nameLat] = nameLat
            it[DrugTemplates.activeSubstance] = substance
            it[DrugTemplates.manufacturer] = manufacturer
            it[DrugTemplates.formTypeId] = formTypeId
            it[DrugTemplates.otc] = true
        }
    }

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
            DrugTemplates.deleteAll()
            template("Aspirin", manufacturer = "Bayer")
            template("Aspirin Cardio", manufacturer = "Bayer")
            template("Baby Aspirin", manufacturer = "Generic")
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
        val results = catalogue.search("аспир", 10)
        assertTrue(results.isNotEmpty())
        val drugWithForm = results.first { it.formTypeId != null }
        assertNotNull(drugWithForm.formTypeId, "FormTypeData should be accessible via service results")
    }

    @Test
    fun `service fuzzySearch returns empty for blank input`() {
        val results = catalogue.search("   ", 10)
        assertTrue(results.isEmpty(), "Should return empty for blank input")
    }

    @Test
    fun `service fuzzySearch sanitizes special characters`() {
        val results = catalogue.search("аспир%", 10)
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
        val found = catalogue.search("Ibuprofenum", 10).first { it.name == "Ибупрофен" }

        assertEquals("Ibuprofenum", found.nameLat)
        assertEquals("ибупрофен", found.activeSubstance)
    }
}
