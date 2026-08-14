-- Схема БД приложения MedAppServer.
--
-- Применяется при инициализации Postgres в compose, поэтому на чистой машине схема
-- появляется сама. Источник истины — JPA-сущности в src/main/kotlin/.../db/model:
-- при их изменении править и этот файл, иначе prod с ddl-auto=validate не поднимется.
--
-- Проверка после правки: применить на пустую БД и убедиться, что приложение стартует
-- с ddl-auto=validate.

-- Нужен для поиска по справочнику: VidalDrugRepository вызывает similarity().
CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ============================================================
-- Аптечки пользователей
-- ============================================================

-- Персональных данных нет по замыслу: только идентификатор и хеш ключа.
CREATE TABLE users
(
    id         uuid         NOT NULL,
    hashed_key varchar(255) NOT NULL,

    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT ix_users_hashed_key UNIQUE (hashed_key)
);

-- У аптечки нет ни владельца, ни названия: участники равноправны.
CREATE TABLE med_kits
(
    id uuid NOT NULL,

    CONSTRAINT med_kits_pkey PRIMARY KEY (id)
);

CREATE TABLE user_med_kits
(
    med_kit_id uuid NOT NULL,
    user_id    uuid NOT NULL,

    CONSTRAINT user_med_kits_pkey PRIMARY KEY (med_kit_id, user_id),
    CONSTRAINT user_med_kits_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits (id),
    CONSTRAINT user_med_kits_user_fkey FOREIGN KEY (user_id) REFERENCES users (id)
);

-- Препарат в аптечке. Название и производитель — свободный текст, а не ссылка на
-- справочник: препарат можно добавить руками, не найдя его в каталоге.
CREATE TABLE user_drugs
(
    id            uuid             NOT NULL,
    name          varchar(300)     NOT NULL,
    quantity      numeric(19, 6)   NOT NULL,
    quantity_unit varchar(50)      NOT NULL,
    form_type     varchar(100),
    category      varchar(200),
    manufacturer  varchar(300),
    country       varchar(100),
    description   text,
    med_kit_id    uuid             NOT NULL,

    CONSTRAINT user_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT user_drugs_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits (id)
);

CREATE INDEX ix_user_drugs_name ON user_drugs (name);
CREATE INDEX ix_user_drugs_med_kit_id ON user_drugs (med_kit_id);

-- План лечения: сколько препарата пользователь зарезервировал под себя. Расписание
-- приёма живёт на клиенте, здесь только количество.
CREATE TABLE usings
(
    user_id        uuid                        NOT NULL,
    drug_id        uuid                        NOT NULL,
    planned_amount numeric(19, 6) NOT NULL,

    CONSTRAINT usings_pkey PRIMARY KEY (drug_id, user_id),
    CONSTRAINT usings_user_fkey FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT usings_drug_fkey FOREIGN KEY (drug_id) REFERENCES user_drugs (id)
);

CREATE INDEX ix_usings_user_id ON usings (user_id);
CREATE INDEX ix_usings_drug_id ON usings (drug_id);


-- ============================================================
-- Справочник препаратов (данные из скраппера Vidal)
-- ============================================================

-- IF NOT EXISTS у этих двух таблиц не для красоты: их создаёт и наполняет дамп
-- справочника (01-load-catalogue.sh), который применяется раньше. Без IF NOT EXISTS
-- второй по порядку скрипт падал бы с "relation already exists" — проверено в обоих
-- направлениях. Определения ниже совпадают с дамповыми, поэтому файл остаётся
-- самодостаточным и когда дампа нет.
CREATE TABLE IF NOT EXISTS form_types
(
    id   uuid         NOT NULL,
    name varchar(100) NOT NULL,

    CONSTRAINT form_types_pkey PRIMARY KEY (id),
    CONSTRAINT form_types_name_key UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS quantity_units
(
    id   uuid        NOT NULL,
    name varchar(30) NOT NULL,

    CONSTRAINT quantity_units_pkey PRIMARY KEY (id),
    CONSTRAINT quantity_units_name_key UNIQUE (name)
);

CREATE TABLE parsed_drugs
(
    id               uuid         NOT NULL,
    name             varchar(300) NOT NULL,
    -- Международное название латиницей; в справочнике заполнено не всюду.
    name_lat         varchar(300),
    form_type_id     uuid,
    quantity         integer,
    quantity_unit_id uuid,
    active_substance varchar(300),
    category         varchar(300),
    manufacturer     varchar(300) NOT NULL,
    country          varchar(100),
    description      text,
    otc              boolean      NOT NULL,

    -- Склейка искомых полей для полнотекстового поиска, считает база.
    --
    -- Колонка, а не выражение в индексе, по двум причинам. Первая: индекс по выражению
    -- Hibernate при старте не может сопоставить с колонкой и пишет HHH000475. Вторая:
    -- выражение пришлось бы повторять в запросе посимвольно, иначе планировщик не сопоставит
    -- его с индексом, — а такое совпадение ломается при первой же правке.
    --
    -- В сущности VidalDrug колонки нет намеренно: приложение её не читает и не пишет.
    -- ddl-auto=validate лишние колонки игнорирует, а метаданные таблицы Hibernate берёт у
    -- JDBC, поэтому для индекса ей достаточно существовать в базе.
    --
    -- Конфигурация 'simple', а не 'russian': названия препаратов и производителей — имена
    -- собственные, стеммить их незачем, а предсказуемость дороже.
    search_tsv       tsvector GENERATED ALWAYS AS (
        to_tsvector('simple',
                    coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                    coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
        ) STORED,

    CONSTRAINT parsed_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT parsed_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types (id),
    CONSTRAINT parsed_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units (id)
);

-- Имена по своей таблице: ix_drugs_* сталкивались с индексами таблицы drugs из дампа
-- справочника, а имена индексов в Postgres уникальны на схему.
CREATE INDEX ix_parsed_drugs_name ON parsed_drugs (name);
CREATE INDEX ix_parsed_drugs_form_type_id ON parsed_drugs (form_type_id);
CREATE INDEX ix_parsed_drugs_quantity_unit_id ON parsed_drugs (quantity_unit_id);

-- Индексы поиска.
--
-- Btree по active_substance и manufacturer убраны: запрос ищет подстроку и похожесть, а
-- btree не работает ни для `LIKE '%…%'`, ни для similarity(). Они только замедляли вставку.
-- Btree по name оставлен — им пользуются ORDER BY name и точное сравнение.
--
-- По самим колонкам, без lower(). pg_trgm приводит вход к нижнему регистру сам:
-- similarity('АСПИРИН','аспирин') равна 1, show_trgm('ASP') совпадает с show_trgm('asp').
-- Поэтому обёртка lower() ничего не давала, зато делала индекс индексом по выражению — а по
-- такому Hibernate при старте не может сопоставить колонку и пишет HHH000475 на каждый.
-- Не возвращать «для надёжности»: надёжности она не добавляет, а шум в логе возвращает.
--
-- Триграммы отдельно по каждому полю, а не по склейке: similarity() делит на общее число
-- триграмм, поэтому на конкатенации четырёх полей сходство размывается почти до нуля и
-- поиск с опечаткой перестаёт срабатывать. Склейка годится только для полнотекстового
-- поиска, где длина значения не влияет на попадание.
--
-- Тип индекса и opclass в JPA не выражаются, поэтому эти индексы живут только здесь;
-- в @Table(indexes = ...) у VidalDrug стоит ссылка на этот файл.
--
-- Полнотекстовый — по колонке search_tsv, см. её объявление выше: нужен для многословных
-- запросов, где слова лежат в разных полях.
CREATE INDEX ix_parsed_drugs_search_tsv ON parsed_drugs USING gin (search_tsv);
CREATE INDEX ix_parsed_drugs_name_trgm ON parsed_drugs USING gin (name gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_name_lat_trgm ON parsed_drugs USING gin (name_lat gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_substance_trgm ON parsed_drugs USING gin (active_substance gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_manufacturer_trgm ON parsed_drugs USING gin (manufacturer gin_trgm_ops);
