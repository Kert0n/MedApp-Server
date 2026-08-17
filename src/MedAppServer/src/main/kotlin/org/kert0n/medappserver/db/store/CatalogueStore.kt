package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище справочника.
 *
 * Единственное место, где наружу выходит сама сущность, а не доменный тип. Заводить для
 * карточки справочника отдельный тип не за что: правил у неё нет, менять её через приложение
 * нечем — справочник только читается и наполняется отдельным импортом. Форма и единица
 * измерения при этом остаются собой (`FormType`, `QuantityUnit`), а не превращаются в строки
 * на полпути.
 *
 * Связи отображены `EAGER`, поэтому за границей транзакции карточка ничего не догружает.
 */
@Component
class CatalogueStore(private val templates: VidalDrugRepository) {

    fun findById(id: UUID): VidalDrug? = templates.findByIdOrNull(id)

    fun search(term: String, likeTerm: String, limit: Int): List<VidalDrug> =
        templates.fuzzySearch(term, likeTerm, limit)
}
