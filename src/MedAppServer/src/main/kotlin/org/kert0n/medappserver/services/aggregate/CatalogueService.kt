package org.kert0n.medappserver.services.aggregate

import kotlin.uuid.Uuid
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.UnknownFormType
import org.kert0n.medappserver.domain.UnknownQuantityUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogueService(private val catalogue: CatalogueStore) {

    /**
     * Поиск по словам запроса.
     *
     * Что считать словом — решение политики поиска, а не хранилища, поэтому разбор живёт здесь.
     * Слова короче трёх букв отбрасываются: триграмм из них не выходит, а в кандидаты они
     * тянут пол-справочника. Если после отбрасывания не осталось ничего, весь запрос идёт одним
     * словом — лучше поискать плохо, чем не поискать вовсе.
     *
     * Слов не больше восьми: иначе одна строка из поисковой формы разворачивается в
     * произвольное число обращений к индексу.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun fuzzySearch(searchTerm: String, limit: Int = DEFAULT_LIMIT): List<DrugTemplate> {
        val query = searchTerm.trim()
        if (query.isBlank()) {
            return emptyList()
        }
        return catalogue.searchTemplates(query, wordsOf(query), clampLimit(limit))
    }

    private fun wordsOf(query: String): List<String> =
        query.split(WORDS)
            .filter { it.length >= MIN_WORD_LENGTH }
            .distinct()
            .take(MAX_WORDS)
            .ifEmpty { listOf(query) }

    /** Карточка справочника или `null`: отсутствие обрабатывает вызывающий. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun find(id: Uuid): DrugTemplate? = catalogue.findTemplate(id)

    /** Словари, из которых клиент выбирает единицу и форму: препарат ссылается на них по id. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun quantityUnits(): List<QuantityUnit> = catalogue.quantityUnits()

    @Transactional(propagation = MANDATORY, readOnly = true)
    fun formTypes(): List<FormType> = catalogue.formTypes()

    @Transactional(propagation = MANDATORY, readOnly = true)
    fun requireQuantityUnit(id: Uuid): QuantityUnit =
        catalogue.findQuantityUnit(id) ?: throw UnknownQuantityUnit()

    @Transactional(propagation = MANDATORY, readOnly = true)
    fun requireFormType(id: Uuid): FormType =
        catalogue.findFormType(id) ?: throw UnknownFormType()

    /**
     * Границы проверяет и контроллер, но сервис вызывается не только из HTTP: `LIMIT -1` —
     * ошибка базы, а большой лимит — рычаг на память.
     */
    private fun clampLimit(limit: Int): Int = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 10
        const val MIN_WORD_LENGTH = 3
        const val MAX_WORDS = 8
        val WORDS = Regex("\\s+")
    }
}
