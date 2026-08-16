package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface VidalDrugRepository : JpaRepository<VidalDrug, UUID> {

    /**
     * Full-text search handles multi-word matches; trigram operators handle typos and
     * substrings. [term] is raw search text, while [likeTerm] has LIKE metacharacters
     * escaped. Ranking is deterministic and favours exact name, prefix, substring,
     * international name, active substance and manufacturer in that order.
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
        @Param("limit") limit: Int = 10
    ): List<VidalDrug>
}
