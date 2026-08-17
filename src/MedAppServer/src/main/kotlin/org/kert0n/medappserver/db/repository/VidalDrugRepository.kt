package org.kert0n.medappserver.db.repository

import java.util.*
import org.kert0n.medappserver.db.model.parsed.DrugTemplateData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface VidalDrugRepository : JpaRepository<DrugTemplateData, UUID> {

    /**
     * Поиск по названию, латинскому названию, действующему веществу и производителю.
     *
     * [term] уходит в полнотекстовый и trigram-поиск, [likeTerm] экранирован для `ILIKE`.
     * Ранжирование: совпадение по словам, приоритет поля, близость по триграммам, имя — чтобы
     * точное совпадение не оказалось ниже опечатки.
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
