package org.kert0n.medappserver.services.aggregate

import java.util.UUID
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.QuantityUnit
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
    fun fuzzySearch(searchTerm: String, limit: Int = DEFAULT_LIMIT): List<DrugTemplate> {
        val term = searchTerm.trim()
        if (term.isBlank()) {
            return emptyList()
        }
            //TODO proper sanitize
        val likeTerm = term
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return catalogue.searchTemplates(term, likeTerm, clampLimit(limit))
    }

    /** Карточка справочника или `null`: отсутствие обрабатывает вызывающий. */
    @Transactional(readOnly = true)
    fun find(id: UUID): DrugTemplate? = catalogue.findTemplate(id)

    /** Словари, из которых клиент выбирает единицу и форму: препарат ссылается на них по id. */
    @Transactional(readOnly = true)
    fun quantityUnits(): List<QuantityUnit> = catalogue.quantityUnits()

    @Transactional(readOnly = true)
    fun formTypes(): List<FormType> = catalogue.formTypes()

    @Transactional(readOnly = true)
    fun requireQuantityUnit(id: UUID): QuantityUnit = catalogue.requireQuantityUnit(id)

    @Transactional(readOnly = true)
    fun requireFormType(id: UUID): FormType = catalogue.requireFormType(id)

    /**
     * Границы проверяет и контроллер, но сервис вызывается не только из HTTP: `LIMIT -1` —
     * ошибка базы, а большой лимит — рычаг на память.
     */
    private fun clampLimit(limit: Int): Int = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 10
    }
}
