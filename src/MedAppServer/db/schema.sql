-- Схема базы MedAppServer.
--
-- ФАЙЛ ПОРОЖДАЁТСЯ. Руками не правится: схему описывают объекты Table в
-- src/main/kotlin/.../db/tables. После правки — ./gradlew test -DupdateSchema=true,
-- и перегенерированный файл кладётся в тот же коммит; за этим следит
-- SchemaSnapshotTest.
--
-- Применяется при инициализации Postgres в compose, поэтому на чистой машине схема
-- появляется сама.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS users (
    id uuid,
    hashed_key VARCHAR(255) NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id)
);

ALTER TABLE users ADD CONSTRAINT ix_users_hashed_key UNIQUE (hashed_key);

CREATE TABLE IF NOT EXISTS med_kits (
    id uuid,
    "version" BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT med_kits_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS user_med_kits (
    med_kit_id uuid,
    user_id uuid,
    CONSTRAINT user_med_kits_pkey PRIMARY KEY (med_kit_id, user_id),
    CONSTRAINT user_med_kits_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits(id) ON DELETE CASCADE,
    CONSTRAINT user_med_kits_user_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS form_types (
    id uuid,
    "name" VARCHAR(100) NOT NULL,
    CONSTRAINT form_types_pkey PRIMARY KEY (id)
);

ALTER TABLE form_types ADD CONSTRAINT form_types_name_key UNIQUE ("name");

CREATE TABLE IF NOT EXISTS quantity_units (
    id uuid,
    "name" VARCHAR(30) NOT NULL,
    CONSTRAINT quantity_units_pkey PRIMARY KEY (id)
);

ALTER TABLE quantity_units ADD CONSTRAINT quantity_units_name_key UNIQUE ("name");

CREATE TABLE IF NOT EXISTS user_drugs (
    id uuid,
    "name" VARCHAR(300) NOT NULL,
    quantity DECIMAL(19, 6) NOT NULL,
    quantity_unit_id uuid NOT NULL,
    form_type_id uuid NULL,
    category VARCHAR(200) NULL,
    manufacturer VARCHAR(300) NULL,
    country VARCHAR(100) NULL,
    description TEXT NULL,
    med_kit_id uuid NOT NULL,
    "version" BIGINT DEFAULT 0 NOT NULL,
    reservations_version BIGINT DEFAULT 0 NOT NULL,
    reservations_total DECIMAL(19, 6) DEFAULT 0 NOT NULL,
    CONSTRAINT user_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT user_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units(id),
    CONSTRAINT user_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types(id),
    CONSTRAINT user_drugs_med_kit_fkey FOREIGN KEY (med_kit_id) REFERENCES med_kits(id) ON DELETE CASCADE,
    CONSTRAINT user_drugs_quantity_positive CHECK (quantity > 0),
    CONSTRAINT user_drugs_reservations_total_not_negative CHECK (reservations_total >= 0)
);

CREATE INDEX ix_user_drugs_name ON user_drugs ("name");

CREATE INDEX ix_user_drugs_med_kit_id ON user_drugs (med_kit_id);

ALTER TABLE user_drugs ADD CONSTRAINT user_drugs_id_med_kit_key UNIQUE (id, med_kit_id);

CREATE TABLE IF NOT EXISTS reservations (
    user_id uuid,
    drug_id uuid,
    med_kit_id uuid NOT NULL,
    amount DECIMAL(19, 6) NOT NULL,
    CONSTRAINT reservations_pkey PRIMARY KEY (drug_id, user_id),
    CONSTRAINT reservations_drug_med_kit_fkey FOREIGN KEY (drug_id, med_kit_id) REFERENCES user_drugs(id, med_kit_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT reservations_membership_fkey FOREIGN KEY (med_kit_id, user_id) REFERENCES user_med_kits(med_kit_id, user_id) ON DELETE CASCADE,
    CONSTRAINT reservations_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_reservations_med_kit_user_id ON reservations (med_kit_id, user_id);

CREATE INDEX ix_reservations_user_id ON reservations (user_id);

CREATE TABLE IF NOT EXISTS parsed_drugs (
    id uuid,
    "name" VARCHAR(300) NOT NULL,
    name_lat VARCHAR(300) NULL,
    form_type_id uuid NULL,
    quantity INT NULL,
    quantity_unit_id uuid NULL,
    active_substance VARCHAR(300) NULL,
    category VARCHAR(300) NULL,
    manufacturer VARCHAR(300) NOT NULL,
    country VARCHAR(100) NULL,
    description TEXT NULL,
    otc BOOLEAN NOT NULL,
    CONSTRAINT parsed_drugs_pkey PRIMARY KEY (id),
    CONSTRAINT parsed_drugs_form_type_fkey FOREIGN KEY (form_type_id) REFERENCES form_types(id),
    CONSTRAINT parsed_drugs_quantity_unit_fkey FOREIGN KEY (quantity_unit_id) REFERENCES quantity_units(id)
);

CREATE INDEX ix_parsed_drugs_name ON parsed_drugs ("name");

CREATE INDEX ix_parsed_drugs_form_type_id ON parsed_drugs (form_type_id);

CREATE INDEX ix_parsed_drugs_quantity_unit_id ON parsed_drugs (quantity_unit_id);

ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple',
                    coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
                    coalesce(active_substance, '') || ' ' || coalesce(manufacturer, ''))
    ) STORED;

ALTER TABLE parsed_drugs ADD COLUMN IF NOT EXISTS search_text text
    GENERATED ALWAYS AS (
        coalesce(name, '') || ' ' || coalesce(name_lat, '') || ' ' ||
        coalesce(active_substance, '') || ' ' || coalesce(manufacturer, '')
    ) STORED;

CREATE INDEX IF NOT EXISTS ix_parsed_drugs_search_tsv
    ON parsed_drugs USING gin (search_tsv);

CREATE INDEX IF NOT EXISTS ix_parsed_drugs_search_text_trgm
    ON parsed_drugs USING gin (search_text gin_trgm_ops);
