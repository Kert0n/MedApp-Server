package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.springframework.stereotype.Service
import java.util.*

@Service
class VidalDrugService(
    private val vidalDrugRepository: VidalDrugRepository
) {

    /** Searches catalogue text while treating LIKE metacharacters as literal input. */
    fun fuzzySearch(searchTerm: String, limit: Int = 10): List<VidalDrug> {
        val term = searchTerm.trim()
        if (term.isBlank()) {
            return emptyList()
        }
        val likeTerm = term
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return vidalDrugRepository.fuzzySearch(term, likeTerm, limit)
    }

    fun findById(id: UUID): VidalDrug? {
        return vidalDrugRepository.findById(id).orElse(null)
    }

}
