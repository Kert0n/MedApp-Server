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
    form_type_id     uuid,
    quantity         integer,
    quantity_unit_id uuid,
    active_substance varchar(300),
    category         varchar(300),
    manufacturer     varchar(300) NOT NULL,
    country          varchar(100),
    description      text,
    otc              boolean      NOT NULL,

    CONSTRAINT parsed_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT parsed_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types (id),
    CONSTRAINT parsed_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units (id)
);

-- Имена по своей таблице: ix_drugs_* сталкивались с индексами таблицы drugs из дампа
-- справочника, а имена индексов в Postgres уникальны на схему.
CREATE INDEX ix_parsed_drugs_name ON parsed_drugs (name);
CREATE INDEX ix_parsed_drugs_active_substance ON parsed_drugs (active_substance);
CREATE INDEX ix_parsed_drugs_manufacturer ON parsed_drugs (manufacturer);
CREATE INDEX ix_parsed_drugs_form_type_id ON parsed_drugs (form_type_id);
CREATE INDEX ix_parsed_drugs_quantity_unit_id ON parsed_drugs (quantity_unit_id);
