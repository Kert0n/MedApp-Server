package org.kert0n.medappserver.services.aggregate

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.domain.DrugTemplate
import org.mockito.kotlin.*

class CatalogueServiceTest {

    private val catalogueStore: CatalogueStore = mock()
    private val catalogueService = CatalogueService(catalogueStore)

    @Test
    fun `fuzzySearch returns empty for blank input`() {
        val result = catalogueService.fuzzySearch("   ", 10)
        assertTrue(result.isEmpty())
        verify(catalogueStore, never()).searchTemplates(any(), any(), any())
    }

    @Test
    fun `fuzzySearch returns empty for empty string`() {
        val result = catalogueService.fuzzySearch("", 10)
        assertTrue(result.isEmpty())
        verify(catalogueStore, never()).searchTemplates(any(), any(), any())
    }

    @Test
    fun `fuzzySearch trims input before querying`() {
        catalogueService.fuzzySearch("  аспир  ", 10)
        verify(catalogueStore).searchTemplates("аспир", listOf("аспир"), 10)
    }

    /**
     * Запрос уезжает вниз словами и целиком: слова — чтобы искать, целое — чтобы отличить
     * набранное точно от похожего. Экранирование `LIKE` сюда не относится: оно свойство
     * оператора и живёт в хранилище.
     */
    @Test
    fun `fuzzySearch splits the query into words`() {
        catalogueService.fuzzySearch("аспирин байер", 10)
        verify(catalogueStore).searchTemplates("аспирин байер", listOf("аспирин", "байер"), 10)
    }

    /** Слова короче трёх букв в кандидаты тянут пол-справочника и потому отбрасываются. */
    @Test
    fun `fuzzySearch drops words too short for trigrams`() {
        catalogueService.fuzzySearch("аспирин от 10", 10)
        verify(catalogueStore).searchTemplates("аспирин от 10", listOf("аспирин"), 10)
    }

    /** Если после отбрасывания не осталось ничего, ищем всем запросом целиком. */
    @Test
    fun `fuzzySearch falls back to the whole query when no word survives`() {
        catalogueService.fuzzySearch("от 10", 10)
        verify(catalogueStore).searchTemplates("от 10", listOf("от 10"), 10)
    }

    @Test
    fun `fuzzySearch returns repository results`() {
        val template = DrugTemplate(
            Uuid.random(), "Аспирин", null, null, null, null, null, "Байер", null, null
        )
        whenever(catalogueStore.searchTemplates("аспир", listOf("аспир"), 10)).thenReturn(listOf(template))

        val result = catalogueService.fuzzySearch("аспир", 10)
        assertEquals(1, result.size)
        assertEquals("Аспирин", result[0].name)
    }

    @Test
    fun `fuzzySearch passes limit to repository`() {
        catalogueService.fuzzySearch("test", 5)
        verify(catalogueStore).searchTemplates("test", listOf("test"), 5)
    }

    /**
     * Границы проверяет и контроллер, но сервис вызывается не только из HTTP: отрицательный
     * лимит доходит до базы как `LIMIT -1`, большой — рычаг на память.
     */
    @Test
    fun `fuzzySearch clamps limit to supported range`() {
        catalogueService.fuzzySearch("test", 0)
        verify(catalogueStore).searchTemplates("test", listOf("test"), 1)

        catalogueService.fuzzySearch("test", -1)
        verify(catalogueStore, times(2)).searchTemplates("test", listOf("test"), 1)

        catalogueService.fuzzySearch("test", 10_000)
        verify(catalogueStore).searchTemplates("test", listOf("test"), 50)
    }

    @Test
    fun `findView returns entry when found`() {
        val id = Uuid.random()
        val drug = DrugTemplate(id, "Test", null, null, null, null, null, "Pharma", null, null)
        whenever(catalogueStore.findTemplate(id)).thenReturn(drug)

        val result = catalogueService.find(id)
        assertNotNull(result)
        assertEquals(id, result.id)
    }

    @Test
    fun `findView returns null when not found`() {
        val id = Uuid.random()
        whenever(catalogueStore.findTemplate(id)).thenReturn(null)

        val result = catalogueService.find(id)
        assertNull(result)
    }
}
