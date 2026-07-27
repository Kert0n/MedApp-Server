package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.parsed.VidalDrug
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface VidalDrugRepository : JpaRepository<VidalDrug, UUID> {

    /**
     * Поиск по справочнику: название, латинское название, действующее вещество, производитель.
     *
     * Два параметра вместо одного не случайность. [term] идёт в `plainto_tsquery` и
     * `similarity()`, где спецсимволы `LIKE` не имеют смысла и только портят сравнение;
     * [likeTerm] экранирован для `LIKE` (см. `VidalDrugService`). Один параметр на оба случая
     * означал бы, что либо `LIKE` небезопасен, либо в триграммы уезжают обратные слэши.
     *
     * Инструменты разные, потому что задачи разные:
     *  - `to_tsvector @@ plainto_tsquery` — многословный запрос. Слова соединяются через AND,
     *    поэтому «Rinzasip Хемофарм» находит запись, где одно слово в name_lat, а другое в
     *    manufacturer. Ни `LIKE`, ни `similarity()` так не умеют: они сравнивают с одним
     *    полем целиком;
     *  - `%` и `LIKE` по каждому полю — опечатки и подстрока. Оператор `%`, а не
     *    `similarity(...) > 0.3`: порог тот же (`pg_trgm.similarity_threshold`), но индекс
     *    поддерживает оператор, а не функцию. На 18 тысячах строк это разница между
     *    обращением к индексу и последовательным сканированием.
     *
     * Порядок выдачи:
     *  1. записи, где нашлись **все** слова запроса, — выше одиночных совпадений;
     *  2. приоритет поля: точное имя → префикс → вхождение → латинское название →
     *     действующее вещество → производитель. Без него запрос «Хемофарм» вытолкнул бы
     *     наверх случайный препарат этого производителя, оттеснив совпадение по названию;
     *  3. лучшее сходство среди четырёх полей;
     *  4. имя — чтобы порядок был устойчив между запусками.
     *
     * Выражение `to_tsvector(...)` обязано посимвольно совпадать с индексом
     * `ix_parsed_drugs_search_tsv` в `db/schema.sql`, иначе планировщик его не подхватит.
     */
    @Query(
        value = """
        SELECT * FROM parsed_drugs
        WHERE to_tsvector('simple',
                coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
              @@ plainto_tsquery('simple', :term)
           OR lower(name) LIKE CONCAT('%', lower(:likeTerm), '%')
           OR lower(name_lat) LIKE CONCAT('%', lower(:likeTerm), '%')
           OR lower(active_substance) LIKE CONCAT('%', lower(:likeTerm), '%')
           OR lower(manufacturer) LIKE CONCAT('%', lower(:likeTerm), '%')
           OR lower(name) % lower(:term)
           OR lower(name_lat) % lower(:term)
           OR lower(active_substance) % lower(:term)
           OR lower(manufacturer) % lower(:term)
        ORDER BY
            (to_tsvector('simple',
                coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
             @@ plainto_tsquery('simple', :term)) DESC,
            CASE
                WHEN lower(name) = lower(:term) THEN 0
                WHEN lower(name) LIKE CONCAT(lower(:likeTerm), '%') THEN 1
                WHEN lower(name) LIKE CONCAT('%', lower(:likeTerm), '%') THEN 2
                WHEN lower(name_lat) LIKE CONCAT('%', lower(:likeTerm), '%') THEN 3
                WHEN lower(active_substance) LIKE CONCAT('%', lower(:likeTerm), '%') THEN 4
                WHEN lower(manufacturer) LIKE CONCAT('%', lower(:likeTerm), '%') THEN 5
                ELSE 6
            END,
            GREATEST(
                similarity(lower(name), lower(:term)),
                similarity(lower(coalesce(name_lat, '')), lower(:term)),
                similarity(lower(coalesce(active_substance, '')), lower(:term)),
                similarity(lower(coalesce(manufacturer, '')), lower(:term))
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
