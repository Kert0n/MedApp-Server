package org.kert0n.medappserver.services.application

import kotlin.uuid.Uuid
import org.kert0n.medappserver.api.DrugTemplateDTO
import org.kert0n.medappserver.api.VocabularyEntryDTO
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.CatalogueService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Справочник и общие словари для клиента.
 *
 * Тонкий до предела: у справочника один агрегат и ни одного сценария — он только читается.
 * Существует ради двух вещей, которые нужны всем фасадам одинаково: границы транзакции и
 * перевода в DTO, который иначе оставался бы в контроллере.
 */
@Service
class CatalogueApplicationService(private val catalogue: CatalogueService) {

    @Transactional(readOnly = true)
    fun search(query: String, limit: Int): List<DrugTemplateDTO> =
        catalogue.fuzzySearch(query, limit).map { it.toDto() }

    @Transactional(readOnly = true)
    fun template(templateId: Uuid): DrugTemplateDTO? = catalogue.find(templateId)?.toDto()

    @Transactional(readOnly = true)
    fun quantityUnits(): List<VocabularyEntryDTO> = catalogue.quantityUnits().map { it.toDto() }

    @Transactional(readOnly = true)
    fun formTypes(): List<VocabularyEntryDTO> = catalogue.formTypes().map { it.toDto() }
}
