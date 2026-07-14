CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    hashed_key VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX ix_users_hashed_key ON users (hashed_key);

CREATE TABLE med_kits (
    id UUID PRIMARY KEY
);

CREATE TABLE user_med_kits (
    user_id UUID NOT NULL REFERENCES users(id),
    med_kit_id UUID NOT NULL REFERENCES med_kits(id),
    PRIMARY KEY (user_id, med_kit_id)
);
CREATE INDEX ix_user_med_kits_med_kit_id ON user_med_kits (med_kit_id);

CREATE TABLE user_drugs (
    id UUID PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    quantity_unit VARCHAR(50) NOT NULL,
    form_type VARCHAR(100),
    category VARCHAR(200),
    manufacturer VARCHAR(300),
    country VARCHAR(100),
    description TEXT,
    med_kit_id UUID NOT NULL REFERENCES med_kits(id) ON DELETE CASCADE
);
CREATE INDEX ix_user_drugs_name ON user_drugs (name);
CREATE INDEX ix_user_drugs_med_kit_id ON user_drugs (med_kit_id);

CREATE TABLE usings (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    drug_id UUID NOT NULL REFERENCES user_drugs(id) ON DELETE CASCADE,
    planned_amount DOUBLE PRECISION NOT NULL,
    last_modified TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, drug_id)
);
CREATE INDEX ix_usings_user_id ON usings (user_id);
CREATE INDEX ix_usings_drug_id ON usings (drug_id);

CREATE TABLE form_types (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT form_types_name_key UNIQUE (name)
);

CREATE TABLE quantity_units (
    id UUID PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    CONSTRAINT quantity_units_name_key UNIQUE (name)
);

CREATE TABLE parsed_drugs (
    id UUID PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    form_type_id UUID REFERENCES form_types(id),
    quantity INTEGER,
    quantity_unit_id UUID REFERENCES quantity_units(id),
    active_substance VARCHAR(300),
    category VARCHAR(300),
    manufacturer VARCHAR(300) NOT NULL,
    country VARCHAR(100),
    description TEXT,
    otc BOOLEAN NOT NULL
);
CREATE INDEX ix_drugs_name ON parsed_drugs (name);
CREATE INDEX idx_drugs_form_type_id ON parsed_drugs (form_type_id);
CREATE INDEX idx_drugs_quantity_unit_id ON parsed_drugs (quantity_unit_id);
CREATE INDEX ix_drugs_active_substance ON parsed_drugs (active_substance);
CREATE INDEX ix_drugs_manufacturer ON parsed_drugs (manufacturer);
CREATE INDEX ix_parsed_drugs_name_trgm ON parsed_drugs USING gin (lower(name) gin_trgm_ops);
