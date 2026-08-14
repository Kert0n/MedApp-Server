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

    /** Карточка справочника или `null`. */
    fun findByIdOrNull(id: UUID): VidalDrug? = vidalDrugRepository.findById(id).orElse(null)

    /**
     * Карточка справочника или 404.
     *
     * Отсутствующая карточка каталога — именно ошибка запроса, а не пустота: справочник
     * статичен, записи из него не удаляются, и tombstone-семантика планов (см.
     * `UsingService.findByUserAndDrugOrNull`) сюда не переносится.
     *
     * Бросает сервис, а не контроллер: так же поступают findById у DrugService и
     * findByIdForUser у MedKitService, и решать это в контроллере значило бы повторять
     * решение в каждом вызывающем.
     */
    fun findById(id: UUID): VidalDrug = findByIdOrNull(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug template not found")

}
