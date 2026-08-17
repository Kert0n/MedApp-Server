package org.kert0n.medappserver.services.models

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.domain.catalogue.DrugTemplate
import org.kert0n.medappserver.db.store.CatalogueStore
import org.mockito.kotlin.*
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueServiceTest {

    private val catalogueStore: CatalogueStore = mock()
    private val catalogueService = CatalogueService(catalogueStore)

    @Test
    fun `fuzzySearch returns empty for blank input`() {
        val result = catalogueService.fuzzySearch("   ", 10)
        assertTrue(result.isEmpty())
        verify(catalogueStore, never()).search(any(), any(), any())
    }

    @Test
    fun `fuzzySearch returns empty for empty string`() {
        val result = catalogueService.fuzzySearch("", 10)
        assertTrue(result.isEmpty())
        verify(catalogueStore, never()).search(any(), any(), any())
    }

    @Test
    fun `fuzzySearch trims input before querying`() {
        catalogueService.fuzzySearch("  аспир  ", 10)
        verify(catalogueStore).search("аспир", "аспир", 10)
    }

    /**
     * Экранируется только вариант для LIKE. Полнотекстовый и trigram-поиск получают сырой
     * термин: обратный слэш там стал бы частью искомого текста, и запрос перестал бы
     * находить то, что ищут.
     */
    @Test
    fun `fuzzySearch escapes LIKE metacharacters only in the LIKE term`() {
        catalogueService.fuzzySearch("test%drug", 10)
        verify(catalogueStore).search("test%drug", "test\\%drug", 10)

        catalogueService.fuzzySearch("test_drug", 10)
        verify(catalogueStore).search("test_drug", "test\\_drug", 10)

        catalogueService.fuzzySearch("test\\drug", 10)
        verify(catalogueStore).search("test\\drug", "test\\\\drug", 10)
    }

    @Test
    fun `fuzzySearch returns repository results`() {
        val template = DrugTemplate(
            UUID.randomUUID(), "Аспирин", null, null, null, null, null, "Байер", null, null
        )
        whenever(catalogueStore.search("аспир", "аспир", 10)).thenReturn(listOf(template))

        val result = catalogueService.fuzzySearch("аспир", 10)
        assertEquals(1, result.size)
        assertEquals("Аспирин", result[0].name)
    }

    @Test
    fun `fuzzySearch passes limit to repository`() {
        catalogueService.fuzzySearch("test", 5)
        verify(catalogueStore).search("test", "test", 5)
    }

    /**
     * Границы проверяет и контроллер, но сервис вызывается не только из HTTP. Отрицательный
     * лимит доходил до базы как `LIMIT -1` и заканчивался ошибкой, большой — рычагом на память.
     */
    @Test
    fun `fuzzySearch clamps limit to supported range`() {
        catalogueService.fuzzySearch("test", 0)
        verify(catalogueStore).search("test", "test", 1)

        catalogueService.fuzzySearch("test", -1)
        verify(catalogueStore, times(2)).search("test", "test", 1)

        catalogueService.fuzzySearch("test", 10_000)
        verify(catalogueStore).search("test", "test", 50)
    }

    @Test
    fun `findView returns entry when found`() {
        val id = UUID.randomUUID()
        val drug = VidalDrug(id = id, name = "Test", manufacturer = "Pharma", otc = true)
        whenever(catalogueStore.findById(id)).thenReturn(
            DrugTemplate(id, "Test", null, null, null, null, null, "Pharma", null, null)
        )

        val result = catalogueService.find(id)
        assertNotNull(result)
        assertEquals(id, result.id)
    }

    @Test
    fun `findView returns null when not found`() {
        val id = UUID.randomUUID()
        whenever(catalogueStore.findById(id)).thenReturn(null)

        val result = catalogueService.find(id)
        assertNull(result)
    }
}
