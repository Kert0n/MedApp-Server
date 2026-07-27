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
     * `lower()` вокруг полей нет намеренно: `pg_trgm` приводит вход к нижнему регистру сам
     * (`similarity('АСПИРИН','аспирин')` равна 1), а `ILIKE` складывает регистр по
     * определению. Обёртка ничего не добавляла, зато превращала индексы в индексы по
     * выражению — по таким Hibernate при старте не может сопоставить колонку и пишет
     * HHH000475 на каждый. Единственное место, где `lower()` уместен, — точное сравнение
     * строк в `ORDER BY`: там регистр действительно надо складывать руками.
     *
     * `search_tsv` — генерируемая колонка, объявлена в `db/schema.sql`. В сущности её нет:
     * приложение её не читает, значение считает база.
     */
    @Query(
        value = """
        SELECT * FROM parsed_drugs
        WHERE search_tsv @@ plainto_tsquery('simple', :term)
           OR name ILIKE CONCAT('%', :likeTerm, '%')
           OR name_lat ILIKE CONCAT('%', :likeTerm, '%')
           OR active_substance ILIKE CONCAT('%', :likeTerm, '%')
           OR manufacturer ILIKE CONCAT('%', :likeTerm, '%')
           OR name % :term
           OR name_lat % :term
           OR active_substance % :term
           OR manufacturer % :term
        ORDER BY
            (search_tsv @@ plainto_tsquery('simple', :term)) DESC,
            CASE
                WHEN lower(name) = lower(:term) THEN 0
                WHEN name ILIKE CONCAT(:likeTerm, '%') THEN 1
                WHEN name ILIKE CONCAT('%', :likeTerm, '%') THEN 2
                WHEN name_lat ILIKE CONCAT('%', :likeTerm, '%') THEN 3
                WHEN active_substance ILIKE CONCAT('%', :likeTerm, '%') THEN 4
                WHEN manufacturer ILIKE CONCAT('%', :likeTerm, '%') THEN 5
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
