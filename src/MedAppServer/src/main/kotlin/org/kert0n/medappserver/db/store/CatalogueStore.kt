package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.kert0n.medappserver.domain.catalogue.DrugTemplate
import org.springframework.stereotype.Component

/**
 * Хранилище справочника.
 *
 * Карточка отдаётся сразу доменным значением. Поиск — нативный запрос, конструктор проекции
 * к нему не приделать, поэтому карточка собирается здесь; названия формы и единицы приходят
 * батчем, а не запросом на строку выдачи.
 */
@Component
class CatalogueStore(private val templates: VidalDrugRepository) {

    fun findById(id: UUID): DrugTemplate? = templates.findViewById(id)

    fun search(term: String, likeTerm: String, limit: Int): List<DrugTemplate> =
        templates.fuzzySearch(term, likeTerm, limit).map { it.toTemplate() }

    private fun VidalDrug.toTemplate() = DrugTemplate(
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
}
