package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.stereotype.Service
import java.util.*

@Service
class VidalDrugService(
    private val vidalDrugRepository: VidalDrugRepository
) {

    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     * Для `LIKE` метасимволы экранируются; полнотекстовый и trigram-поиск получают сырой термин.
     */
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

    /** Карточка справочника или `null`. */
    fun findByIdOrNull(id: UUID): VidalDrug? = vidalDrugRepository.findById(id).orElse(null)

    /** Карточка справочника или 404. */
    fun findById(id: UUID): VidalDrug = findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug template not found")

}
