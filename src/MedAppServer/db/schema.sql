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
    CONSTRAINT user_med_kits_med_kit_fkey
        FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE,
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
    CONSTRAINT user_drugs_med_kit_fkey
        FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE
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
    CONSTRAINT usings_drug_fkey
        FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE
);

CREATE INDEX ix_usings_user_id ON usings (user_id);
CREATE INDEX ix_usings_drug_id ON usings (drug_id);


-- ============================================================
-- Справочник препаратов (данные из скраппера Vidal)
-- ============================================================

-- Таблицы справочника создаются до загрузки data-only дампа. Файл каталога содержит
-- только COPY и ANALYZE, поэтому структура справочника определяется только здесь.
CREATE TABLE form_types
(
    id   uuid         NOT NULL,
    name varchar(100) NOT NULL,

    CONSTRAINT form_types_pkey PRIMARY KEY (id),
    CONSTRAINT form_types_name_key UNIQUE (name)
);

CREATE TABLE quantity_units
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

    -- Генерируемый документ полнотекстового поиска. Конфигурация simple сохраняет
    -- написание торговых названий и производителей без языкового стемминга.
    search_tsv       tsvector GENERATED ALWAYS AS (
        to_tsvector('simple',
                    coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                    coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
        ) STORED,

    CONSTRAINT parsed_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT parsed_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types (id),
    CONSTRAINT parsed_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units (id)
);

-- B-tree индексы для адресных связей, точного поиска и сортировки по имени.
CREATE INDEX ix_parsed_drugs_name ON parsed_drugs (name);
CREATE INDEX ix_parsed_drugs_form_type_id ON parsed_drugs (form_type_id);
CREATE INDEX ix_parsed_drugs_quantity_unit_id ON parsed_drugs (quantity_unit_id);

-- GIN по search_tsv обслуживает многословный поиск по нескольким полям. Отдельные trigram
-- индексы обслуживают ILIKE и similarity по каждому полю без размывания сходства длинной
-- конкатенацией. Opclass этих индексов не выражается JPA-аннотациями.
CREATE INDEX ix_parsed_drugs_search_tsv ON parsed_drugs USING gin (search_tsv);
CREATE INDEX ix_parsed_drugs_name_trgm ON parsed_drugs USING gin (name gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_name_lat_trgm ON parsed_drugs USING gin (name_lat gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_substance_trgm ON parsed_drugs USING gin (active_substance gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_manufacturer_trgm ON parsed_drugs USING gin (manufacturer gin_trgm_ops);
