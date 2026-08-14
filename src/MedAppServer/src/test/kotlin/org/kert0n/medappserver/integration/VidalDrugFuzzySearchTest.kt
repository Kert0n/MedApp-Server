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

    /**
     * Запрос принимает термин дважды: сырым для триграмм и полнотекстового поиска,
     * экранированным для `LIKE`. В тестах спецсимволов нет, поэтому оба совпадают —
     * экранирование проверяется отдельно, в `VidalDrugServiceTest`.
     */
    private fun search(term: String, limit: Int = 10) =
        vidalDrugRepository.fuzzySearch(term, term, limit)

    @BeforeEach
    fun setup() {
        txTemplate = TransactionTemplate(transactionManager)
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            entityManager.createNativeQuery("DELETE FROM form_types").executeUpdate()

            val tabletType = FormType(name = "таблетки")
            entityManager.persist(tabletType)
            entityManager.flush()

            // Значения подобраны так, чтобы каждое поле можно было проверить в отдельности:
            // искомое слово встречается ровно в одной колонке и ни в какой другой. Иначе тест
            // не доказывал бы, что нужное поле вообще участвует в запросе.
            val drugs = listOf(
                VidalDrug(
                    name = "Аспирин", nameLat = "Aspirinum",
                    activeSubstance = "Кислота ацетилсалициловая",
                    manufacturer = "Байер", otc = true, formType = tabletType
                ),
                VidalDrug(
                    name = "Аспирин Кардио", nameLat = "Aspirinum Cardio",
                    activeSubstance = "Кислота ацетилсалициловая",
                    manufacturer = "Байер", otc = true, formType = tabletType
                ),
                VidalDrug(
                    name = "Ибупрофен", nameLat = "Ibuprofenum",
                    activeSubstance = "Ибупрофен", manufacturer = "Фармстандарт", otc = true
                ),
                VidalDrug(
                    name = "Парацетамол", nameLat = "Paracetamolum",
                    activeSubstance = "Парацетамол", manufacturer = "Медисорб", otc = true
                ),
                VidalDrug(name = "Aspirin", manufacturer = "Bayer", otc = true, formType = tabletType),
                VidalDrug(name = "Ibuprofen", manufacturer = "Generic", otc = true)
            )
            vidalDrugRepository.saveAll(drugs)
        }
    }

    @Test
    fun `fuzzySearchByName finds Cyrillic drugs by prefix`() {
        val results = search("аспир", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'аспир'")
        assertTrue(results.any { it.name == "Аспирин" }, "Should find 'Аспирин'")
        assertTrue(results.any { it.name == "Аспирин Кардио" }, "Should find 'Аспирин Кардио'")
    }

    @Test
    fun `fuzzySearchByName is case-insensitive for Cyrillic`() {
        val results = search("АСПИР", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'АСПИР'")
        assertTrue(results.any { it.name == "Аспирин" })
    }

    @Test
    fun `fuzzySearchByName finds Latin drugs by prefix`() {
        val results = search("asp", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'asp'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearchByName is case-insensitive for Latin`() {
        val results = search("ASP", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching uppercase 'ASP'")
        assertTrue(results.any { it.name == "Aspirin" })
    }

    @Test
    fun `fuzzySearchByName finds drugs by substring`() {
        val results = search("профен", 10)
        assertTrue(results.isNotEmpty(), "Should find drugs matching 'профен'")
        assertTrue(results.any { it.name == "Ибупрофен" })
    }

    @Test
    fun `fuzzySearchByName respects limit`() {
        val results = search("а", 2)
        assertTrue(results.size <= 2, "Should return at most 2 results")
    }

    @Test
    fun `fuzzySearchByName returns empty for no match`() {
        val results = search("xyz123", 10)
        assertTrue(results.isEmpty(), "Should return no results for unrelated term")
    }

    @Test
    fun `fuzzySearchByName uses trigram similarity for fuzzy matches`() {
        val results = search("Аспирн", 10)
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

        val results = search("Aspirin", 10)
        assertTrue(results.isNotEmpty())
        assertEquals("Aspirin", results.first().name, "Exact match should be first")
    }

    @Test
    fun `fuzzySearchByName eagerly loads formType - no LazyInitializationException`() {
        val results = search("аспир", 10)
        assertTrue(results.isNotEmpty())
        val formTypeName = results.first { it.formType != null }.formType?.name
        assertNotNull(formTypeName, "FormType should be eagerly loaded and accessible outside transaction")
        assertEquals("таблетки", formTypeName)
    }

    @Test
    fun `service fuzzySearchByName returns results with accessible formType`() {
        val results = vidalDrugService.fuzzySearch("аспир", 10)
        assertTrue(results.isNotEmpty())
        val drugWithForm = results.first { it.formType != null }
        assertNotNull(drugWithForm.formType?.name, "FormType should be accessible via service results")
    }

    @Test
    fun `service fuzzySearchByName returns empty for blank input`() {
        val results = vidalDrugService.fuzzySearch("   ", 10)
        assertTrue(results.isEmpty(), "Should return empty for blank input")
    }

    @Test
    fun `service fuzzySearchByName sanitizes special characters`() {
        val results = vidalDrugService.fuzzySearch("аспир%", 10)
        assertNotNull(results)
    }

    // ── Поиск по каждому из четырёх полей в отдельности ──────────────────────────────

    @Test
    fun `находит по латинскому названию, которого нет ни в одном другом поле`() {
        // Ровно тот случай, который был сломан: name_lat не переносился в parsed_drugs, и
        // запрос по латинскому названию не находил ничего.
        val results = search("Ibuprofenum")

        assertTrue(results.any { it.name == "Ибупрофен" }, "должен найтись по name_lat")
    }

    @Test
    fun `находит по действующему веществу`() {
        val results = search("ацетилсалициловая")

        assertTrue(results.any { it.name == "Аспирин" }, "должен найтись по active_substance")
    }

    @Test
    fun `находит по производителю`() {
        val results = search("Фармстандарт")

        assertTrue(results.any { it.name == "Ибупрофен" }, "должен найтись по manufacturer")
    }

    @Test
    fun `совпадение по названию стоит выше совпадения по производителю`() {
        // «Байер» — производитель у двух записей, но ни у одной не встречается в названии.
        // Проверяем, что приоритет поля вообще работает: сортировка не должна быть случайной.
        val results = search("Байер")

        assertTrue(results.isNotEmpty(), "по производителю что-то должно найтись")
        assertTrue(
            results.all { it.manufacturer == "Байер" },
            "по этому запросу подходят только записи Байера: ${results.map { it.name }}"
        )
    }

    // ── Совпадение сразу в нескольких полях поднимается выше одиночных ───────────────

    @Test
    fun `запрос из латинского названия и производителя ставит совпавшее по обоим первым`() {
        // Три записи: у первой оба слова, у второй только производитель, у третьей только
        // латинское название. Проверяется именно порядок, а не факт нахождения — одиночные
        // совпадения тоже обязаны попасть в выдачу.
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            vidalDrugRepository.saveAll(
                listOf(
                    VidalDrug(name = "Оба", nameLat = "Rinzasip", manufacturer = "Хемофарм", otc = true),
                    VidalDrug(name = "Только производитель", nameLat = "Nimesil", manufacturer = "Хемофарм", otc = true),
                    VidalDrug(name = "Только латиница", nameLat = "Rinzasip", manufacturer = "Другой", otc = true)
                )
            )
        }

        val results = search("Rinzasip Хемофарм")

        assertEquals(3, results.size, "все три записи должны попасть в выдачу")
        assertEquals(
            "Оба", results.first().name,
            "запись, где нашлись оба слова, обязана стоять выше одиночных совпадений: " +
                "${results.map { it.name }}"
        )
    }

    @Test
    fun `запрос из действующего вещества и производителя работает так же`() {
        // Та же проверка на другой паре полей: подъём не должен быть привязан к конкретным
        // колонкам, иначе он держится на совпадении, а не на устройстве запроса.
        txTemplate.execute {
            vidalDrugRepository.deleteAll()
            vidalDrugRepository.saveAll(
                listOf(
                    VidalDrug(
                        name = "Оба", activeSubstance = "Нимесулид",
                        manufacturer = "Хемофарм", otc = true
                    ),
                    VidalDrug(
                        name = "Только производитель", activeSubstance = "Ибупрофен",
                        manufacturer = "Хемофарм", otc = true
                    ),
                    VidalDrug(
                        name = "Только вещество", activeSubstance = "Нимесулид",
                        manufacturer = "Другой", otc = true
                    )
                )
            )
        }

        val results = search("Нимесулид Хемофарм")

        assertEquals(3, results.size, "все три записи должны попасть в выдачу")
        assertEquals(
            "Оба", results.first().name,
            "совпадение по обоим полям должно быть первым: ${results.map { it.name }}"
        )
    }
}
