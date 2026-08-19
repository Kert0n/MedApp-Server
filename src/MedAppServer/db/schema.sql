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
--
-- version — оптимистическая блокировка. Собственная строка аптечки не меняется никогда: её
-- состояние это состав, а он лежит в user_med_kits. Поэтому версию здесь двигает не dirty
-- checking, а явная заявка на изменение корня (OPTIMISTIC_FORCE_INCREMENT) — см. MedKitStore.
CREATE TABLE med_kits
(
    id      uuid   NOT NULL,
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT med_kits_pkey PRIMARY KEY (id)
);

-- Членство. Каскад только со стороны аптечки: удалили аптечку — членства нет.
--
-- Со стороны пользователя каскада намеренно нет, и это относится ко всем FK на users ниже.
-- Удаление пользователя не является операцией API: аптечки общие, и каскад молча вынес бы из
-- чужой аптечки чужие планы. Если такая операция понадобится, она должна быть явной, а не
-- побочным эффектом DELETE.
CREATE TABLE user_med_kits
(
    med_kit_id uuid NOT NULL,
    user_id    uuid NOT NULL,

    CONSTRAINT user_med_kits_pkey PRIMARY KEY (med_kit_id, user_id),
    CONSTRAINT user_med_kits_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE,
    CONSTRAINT user_med_kits_user_fkey FOREIGN KEY (user_id) REFERENCES users (id)
);

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

-- Препарат в аптечке. Название и производитель — свободный текст, а не ссылка на
-- справочник: препарат можно добавить руками, не найдя его в каталоге.
CREATE TABLE user_drugs
(
    id            uuid           NOT NULL,
    name          varchar(300)   NOT NULL,
    -- numeric, а не double precision: количество препарата — это точная величина, и половина
    -- таблетки не должна превращаться в 0.49999999999999994 при первом же делении.
    quantity         numeric(19, 6) NOT NULL,
    -- Единица измерения и форма — ссылки в тот же справочник, которым пользуется каталог:
    -- «шт» у заведённого руками препарата и «шт» у карточки каталога должны быть одной
    -- единицей, а не двумя одинаково написанными строками.
    quantity_unit_id uuid           NOT NULL,
    form_type_id     uuid,
    category         varchar(200),
    manufacturer     varchar(300),
    country          varchar(100),
    description      text,
    med_kit_id       uuid           NOT NULL,
    -- Оптимистическая блокировка. Дочерних строк у упаковки нет, поэтому версию двигает
    -- обычный dirty checking: любая её команда меняет саму эту строку.
    version          bigint         NOT NULL DEFAULT 0,

    CONSTRAINT user_drugs_pkey PRIMARY KEY (id),
    -- Родительская сторона составного ключа брони: бронь ссылается на пару «пачка и её
    -- аптечка», чтобы аптечку в её строке нельзя было рассогласовать с настоящей.
    CONSTRAINT user_drugs_id_med_kit_key UNIQUE (id, med_kit_id),
    -- Пустой упаковки не бывает: опустевшая уничтожается, а не остаётся нулём. Правило
    -- держит домен, здесь оно продублировано затем, что колонку может тронуть и не он:
    -- массовый UPDATE, миграция, рука в psql.
    CONSTRAINT user_drugs_quantity_positive CHECK (quantity > 0),
    CONSTRAINT user_drugs_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE,
    -- Без каскада: словарь переживает препараты, а препарат без единицы измерения
    -- бессмыслен, поэтому удалить используемую единицу база не даст.
    CONSTRAINT user_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units (id),
    CONSTRAINT user_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types (id)
);

CREATE INDEX ix_user_drugs_name ON user_drugs (name);
CREATE INDEX ix_user_drugs_med_kit_id ON user_drugs (med_kit_id);

-- Бронь: сколько из этой упаковки человек считает своим. Расписание приёма живёт на клиенте,
-- здесь только количество.
--
-- Бронь может превышать остаток упаковки, и никакого ограничения на этот счёт в схеме нет:
-- сколько из своей брони оставить, решает её владелец, а не сервер.
--
-- Каскад по drug_id остаётся: бронь на выброшенную пачку бессмысленна. Но это правило
-- целостности данных, а не владения — упаковка бронями не распоряжается и в своём агрегате их
-- не держит.
CREATE TABLE reservations
(
    user_id    uuid           NOT NULL,
    drug_id    uuid           NOT NULL,
    -- Аптечка пачки, скопированная в строку брони. Не свойство брони: назначение живёт парой
    -- «человек и пачка», а хранилище у пачки своё. Копия нужна ключам целостности — без неё
    -- членство и бронь связать нечем.
    med_kit_id uuid           NOT NULL,
    amount     numeric(19, 6) NOT NULL,
    version    bigint         NOT NULL DEFAULT 0,

    CONSTRAINT reservations_pkey PRIMARY KEY (drug_id, user_id),
    -- Брони с нулём не бывает: отмена выражается удалением строки.
    CONSTRAINT reservations_amount_positive CHECK (amount > 0),
    -- Пачка вместе со своей аптечкой. Ключ составной, и это даёт два следствия: копию аптечки
    -- не рассогласовать с настоящей — такой пары нет в родителе; переезд пачки тянет копию за
    -- собой. Правило, стоящее за переездом, написано в домене и в сценарии, здесь — страховка.
    CONSTRAINT reservations_drug_med_kit_fkey FOREIGN KEY (drug_id, med_kit_id)
        REFERENCES user_drugs (id, med_kit_id) ON UPDATE CASCADE ON DELETE CASCADE,
    -- «Нет членства — нет брони». Правило живёт в выходе из аптечки, который снимает брони сам;
    -- ключ страхует от забывчивости и закрывает гонку: вставка удерживает строку членства до
    -- конца транзакции, и одновременный выход её дождётся.
    --
    -- Отдельного ключа на users больше нет: членство и так на них ссылается.
    CONSTRAINT reservations_membership_fkey FOREIGN KEY (med_kit_id, user_id)
        REFERENCES user_med_kits (med_kit_id, user_id) ON DELETE CASCADE
);

CREATE INDEX ix_reservations_user_id ON reservations (user_id);
-- Отдельного индекса по drug_id нет: первичный ключ начинается с него.
CREATE INDEX ix_reservations_med_kit_user_id ON reservations (med_kit_id, user_id);


-- ============================================================
-- Справочник препаратов (данные из скраппера Vidal)
-- ============================================================

CREATE TABLE parsed_drugs
(
    id               uuid         NOT NULL,
    name             varchar(300) NOT NULL,
    -- Международное название латиницей; в справочнике заполнено не всюду. Искать по нему
    -- нужно: пользователь набирает и «Ибупрофен», и «Ibuprofen».
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

    -- Документ полнотекстового поиска, который считает сама база. Конфигурация simple, а не
    -- russian: стемминг ломает торговые названия и фамилии производителей, а искать нужно
    -- именно их написание.
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

-- GIN по search_tsv обслуживает многословный запрос сразу по четырём полям. Отдельные
-- trigram-индексы обслуживают ILIKE и поиск по опечатке для каждого поля по отдельности:
-- в общей склейке сходство размывалось бы длиной документа. Opclass этих индексов
-- аннотациями JPA не выражается, поэтому они живут только здесь.
CREATE INDEX ix_parsed_drugs_search_tsv ON parsed_drugs USING gin (search_tsv);
CREATE INDEX ix_parsed_drugs_name_trgm ON parsed_drugs USING gin (name gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_name_lat_trgm ON parsed_drugs USING gin (name_lat gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_substance_trgm ON parsed_drugs USING gin (active_substance gin_trgm_ops);
CREATE INDEX ix_parsed_drugs_manufacturer_trgm ON parsed_drugs USING gin (manufacturer gin_trgm_ops);
