package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.springframework.stereotype.Service
import java.util.*

@Service
class VidalDrugService(
    private val vidalDrugRepository: VidalDrugRepository
) {

    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     *
     * Запрос получает термин в двух видах. Экранированный нужен для `LIKE`, иначе введённый
     * пользователем `%` превращается в «совпадает с чем угодно». Сырой — для
     * `plainto_tsquery` и `similarity()`: там подстановочных знаков нет, а добавленные
     * обратные слэши попали бы в сравнение как обычные символы и портили бы сходство.
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

    fun findById(id: UUID): VidalDrug? {
        return vidalDrugRepository.findById(id).orElse(null)
    }

}
