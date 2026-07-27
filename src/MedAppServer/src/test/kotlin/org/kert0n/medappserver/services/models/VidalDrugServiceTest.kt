package org.kert0n.medappserver.services.models

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.mockito.kotlin.*
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VidalDrugServiceTest {

    private val vidalDrugRepository: VidalDrugRepository = mock()
    private val vidalDrugService = VidalDrugService(vidalDrugRepository)

    @Test
    fun `fuzzySearch returns empty for blank input`() {
        val result = vidalDrugService.fuzzySearch("   ", 10)
        assertTrue(result.isEmpty())
        verify(vidalDrugRepository, never()).fuzzySearch(any(), any(), any())
    }

    @Test
    fun `fuzzySearch returns empty for empty string`() {
        val result = vidalDrugService.fuzzySearch("", 10)
        assertTrue(result.isEmpty())
        verify(vidalDrugRepository, never()).fuzzySearch(any(), any(), any())
    }

    @Test
    fun `fuzzySearch trims input before querying`() {
        whenever(vidalDrugRepository.fuzzySearch("аспир", "аспир", 10)).thenReturn(emptyList())
        vidalDrugService.fuzzySearch("  аспир  ", 10)
        verify(vidalDrugRepository).fuzzySearch("аспир", "аспир", 10)
    }

    @Test
    fun `fuzzySearch escapes percent character`() {
        whenever(vidalDrugRepository.fuzzySearch("test%drug", "test\\%drug", 10)).thenReturn(emptyList())
        vidalDrugService.fuzzySearch("test%drug", 10)
        verify(vidalDrugRepository).fuzzySearch("test%drug", "test\\%drug", 10)
    }

    @Test
    fun `fuzzySearch escapes underscore character`() {
        whenever(vidalDrugRepository.fuzzySearch("test_drug", "test\\_drug", 10)).thenReturn(emptyList())
        vidalDrugService.fuzzySearch("test_drug", 10)
        verify(vidalDrugRepository).fuzzySearch("test_drug", "test\\_drug", 10)
    }

    @Test
    fun `fuzzySearch escapes backslash character`() {
        whenever(vidalDrugRepository.fuzzySearch("test\\drug", "test\\\\drug", 10)).thenReturn(emptyList())
        vidalDrugService.fuzzySearch("test\\drug", 10)
        verify(vidalDrugRepository).fuzzySearch("test\\drug", "test\\\\drug", 10)
    }

    @Test
    fun `fuzzySearch returns repository results`() {
        val drug = VidalDrug(name = "Аспирин", manufacturer = "Байер", otc = true)
        whenever(vidalDrugRepository.fuzzySearch("аспир", "аспир", 10)).thenReturn(listOf(drug))

        val result = vidalDrugService.fuzzySearch("аспир", 10)
        assertEquals(1, result.size)
        assertEquals("Аспирин", result[0].name)
    }

    @Test
    fun `fuzzySearch passes limit to repository`() {
        whenever(vidalDrugRepository.fuzzySearch("test", "test", 5)).thenReturn(emptyList())
        vidalDrugService.fuzzySearch("test", 5)
        verify(vidalDrugRepository).fuzzySearch("test", "test", 5)
    }

    @Test
    fun `findById returns drug when found`() {
        val id = UUID.randomUUID()
        val drug = VidalDrug(id = id, name = "Test", manufacturer = "Pharma", otc = true)
        whenever(vidalDrugRepository.findById(id)).thenReturn(Optional.of(drug))

        val result = vidalDrugService.findById(id)
        assertNotNull(result)
        assertEquals(id, result.id)
    }

    @Test
    fun `findById returns null when not found`() {
        val id = UUID.randomUUID()
        whenever(vidalDrugRepository.findById(id)).thenReturn(Optional.empty())

        val result = vidalDrugService.findById(id)
        assertNull(result)
    }
}
