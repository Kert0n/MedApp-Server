package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class VidalDrugService(
    private val vidalDrugRepository: VidalDrugRepository
) {

    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     * Для `LIKE` метасимволы экранируются; полнотекстовый и trigram-поиск получают сырой термин.
     */
    @Transactional(readOnly = true)
    fun fuzzySearch(searchTerm: String, limit: Int = 10): List<DrugTemplateView> {
        val term = searchTerm.trim()
        if (term.isBlank()) {
            return emptyList()
        }
        val likeTerm = term
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return vidalDrugRepository.fuzzySearch(term, likeTerm, limit).map(VidalDrug::toView)
    }

    /** Карточка справочника или `null`. */
    @Transactional(readOnly = true)
    fun findByIdOrNull(id: UUID): DrugTemplateView? =
        vidalDrugRepository.findById(id).orElse(null)?.toView()

    /** Карточка справочника или 404. */
    fun findById(id: UUID): DrugTemplateView = findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug template not found")

}

private fun VidalDrug.toView(): DrugTemplateView = DrugTemplateView(
    id = id,
    name = name,
    nameLat = nameLat,
    activeSubstance = activeSubstance,
    formType = formType?.name,
    category = category,
    quantityUnit = quantityUnit?.name,
    manufacturer = manufacturer,
    country = country,
    description = description
)
