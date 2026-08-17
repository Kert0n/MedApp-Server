package org.kert0n.medappserver.services.models

import java.util.UUID
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogueService(private val catalogue: CatalogueStore) {

    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     *
     * Метасимволы экранируются только для `LIKE`; полнотекстовый и trigram-поиск получают
     * сырой термин, иначе обратные слэши попали бы в сам искомый текст.
     */
    @Transactional(readOnly = true)
    fun fuzzySearch(searchTerm: String, limit: Int = DEFAULT_LIMIT): List<VidalDrug> {
        val term = searchTerm.trim()
        if (term.isBlank()) {
            return emptyList()
        }
            //TODO proper sanitize
        val likeTerm = term
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return catalogue.search(term, likeTerm, clampLimit(limit))
    }

    /** Карточка справочника или `null`: отсутствие обрабатывает вызывающий. */
    @Transactional(readOnly = true)
    fun find(id: UUID): VidalDrug? = catalogue.findById(id)

    /**
     * Границы лимита проверяет и контроллер, но полагаться только на него нельзя: `LIMIT -1`
     * заканчивался ошибкой базы, а большой лимит — рычагом на память. Сервис вызывается не
     * только из HTTP, поэтому предел живёт и здесь.
     */
    private fun clampLimit(limit: Int): Int = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 10
    }
}
