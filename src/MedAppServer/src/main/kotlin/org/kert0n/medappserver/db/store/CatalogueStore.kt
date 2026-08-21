package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.parsed.DrugTemplateData
import org.kert0n.medappserver.db.repository.FormTypeRepository
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.repository.VidalDrugRepository
import org.kert0n.medappserver.domain.DrugTemplate
import org.kert0n.medappserver.domain.FormType
import org.kert0n.medappserver.domain.QuantityUnit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище справочника: карточки препаратов и словари единиц и форм.
 *
 * Словари общие буквально — «шт» в каталоге и «шт» у заведённой руками пачки одна и та же строка.
 */
@Component
class CatalogueStore(
    private val templates: VidalDrugRepository,
    private val units: QuantityUnitRepository,
    private val forms: FormTypeRepository
) {

    fun findTemplate(id: UUID): DrugTemplate? = templates.findByIdOrNull(id)?.toDomain()

    fun searchTemplates(term: String, likeTerm: String, limit: Int): List<DrugTemplate> =
        templates.fuzzySearch(term, likeTerm, limit).map { it.toDomain() }

    fun quantityUnits(): List<QuantityUnit> = units.findAll().map { it.toDomain() }.sortedBy { it.name }

    fun formTypes(): List<FormType> = forms.findAll().map { it.toDomain() }.sortedBy { it.name }

    fun findQuantityUnit(id: UUID): QuantityUnit? = units.findByIdOrNull(id)?.toDomain()

    fun findFormType(id: UUID): FormType? = forms.findByIdOrNull(id)?.toDomain()

    private fun DrugTemplateData.toDomain() = DrugTemplate(
        id = id,
        name = name,
        nameLat = nameLat,
        activeSubstance = activeSubstance,
        formType = formType?.toDomain(),
        category = category,
        quantityUnit = quantityUnit?.toDomain(),
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}
