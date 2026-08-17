package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.parsed.DrugTemplateData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface VidalDrugRepository : JpaRepository<DrugTemplateData, UUID> {

    /**
     * Карточка справочника с уже развёрнутыми названиями формы и единицы.
     *
     * Джойн вместо EAGER-связей: поиск — нативный запрос, join fetch к нему не приделать, и
     * каждая строка выдачи тянула за собой отдельную загрузку справочников.
     */


    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     *
     * Раньше искали только по названию, и препарат нельзя было найти ни по действующему
     * веществу, ни по латинскому написанию — а именно так его чаще всего и ищут.
     *
     * [term] уходит в полнотекстовый и trigram-поиск, [likeTerm] экранирован для `ILIKE`.
     * Ранжирование: сначала совпадение по словам, затем приоритет поля, затем близость по
     * триграммам, затем имя — чтобы точное совпадение не оказалось ниже опечатки.
     */
    @Query(
        value = """
        SELECT * FROM parsed_drugs
        WHERE search_tsv @@ plainto_tsquery('simple', :term)
           OR name ILIKE ('%' || :likeTerm || '%')
           OR name_lat ILIKE ('%' || :likeTerm || '%')
           OR active_substance ILIKE ('%' || :likeTerm || '%')
           OR manufacturer ILIKE ('%' || :likeTerm || '%')
           OR name % :term
           OR name_lat % :term
           OR active_substance % :term
           OR manufacturer % :term
        ORDER BY
            (search_tsv @@ plainto_tsquery('simple', :term)) DESC,
            CASE
                WHEN lower(name) = lower(:term) THEN 0
                WHEN name ILIKE (:likeTerm || '%') THEN 1
                WHEN name ILIKE ('%' || :likeTerm || '%') THEN 2
                WHEN name_lat ILIKE ('%' || :likeTerm || '%') THEN 3
                WHEN active_substance ILIKE ('%' || :likeTerm || '%') THEN 4
                WHEN manufacturer ILIKE ('%' || :likeTerm || '%') THEN 5
                ELSE 6
            END,
            GREATEST(
                similarity(name, :term),
                similarity(coalesce(name_lat, ''), :term),
                similarity(coalesce(active_substance, ''), :term),
                similarity(coalesce(manufacturer, ''), :term)
            ) DESC,
            name
        LIMIT :limit
        """,
        nativeQuery = true
    )
    fun fuzzySearch(
        @Param("term") term: String,
        @Param("likeTerm") likeTerm: String,
        @Param("limit") limit: Int
    ): List<DrugTemplateData>
}

/** Общий словарь единиц измерения: тот же, которым пользуется каталог. */
interface QuantityUnitRepository : JpaRepository<org.kert0n.medappserver.db.model.parsed.QuantityUnitData, UUID>

/** Общий словарь форм выпуска. */
interface FormTypeRepository : JpaRepository<org.kert0n.medappserver.db.model.parsed.FormTypeData, UUID>
