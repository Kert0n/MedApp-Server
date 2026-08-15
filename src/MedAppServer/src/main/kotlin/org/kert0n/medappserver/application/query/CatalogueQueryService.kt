package org.kert0n.medappserver.application.query

import org.kert0n.medappserver.application.model.DrugTemplateView
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.kert0n.medappserver.domain.error.DrugTemplateNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CatalogueQueryService(
    private val repository: VidalDrugRepository
) {
    fun search(query: String, limit: Int): List<DrugTemplateView> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val likeTerm = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return repository.fuzzySearch(term, likeTerm, limit).map(::toView)
    }

    fun get(templateId: UUID): DrugTemplateView = repository.findById(templateId)
        .map(::toView)
        .orElseThrow { DrugTemplateNotFound(templateId) }

    private fun toView(drug: VidalDrug): DrugTemplateView = DrugTemplateView(
        id = drug.id,
        name = drug.name,
        formType = drug.formType?.name,
        category = drug.category,
        quantityUnit = drug.quantityUnit?.name,
        manufacturer = drug.manufacturer,
        country = drug.country,
        description = drug.description
    )
}
