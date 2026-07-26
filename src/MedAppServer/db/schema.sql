--
-- Схема приложения MedAppServer.
--
-- ГЕНЕРИРУЕМЫЙ ФАЙЛ. Править руками не нужно: источник истины — JPA-сущности.
-- Пересоздать после изменения сущностей:
--
--   1) поднять чистый Postgres;
--   2) CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- нужен для similarity() в поиске
--   3) запустить приложение с --spring.jpa.hibernate.ddl-auto=create и остановить;
--   4) pg_dump --schema-only --no-owner --no-privileges --no-comments
--      и убрать из вывода \restrict/\unrestrict и строки про версию сервера
--      (они недетерминированы и шумят в диффе).
--
-- Зачем файл нужен. Flyway в проекте сознательно не используется, а ddl-auto=update
-- НИКОГДА не удаляет и не меняет существующие колонки — только добавляет. Поэтому
-- без этого файла не было пути от пустой БД к рабочей схеме, и схемы dev и prod
-- расходились молча. Изменения существующих колонок — отдельными скриптами в
-- db/manual/, см. db/manual/README.md.
--
SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: form_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.form_types (
    id uuid NOT NULL,
    name character varying(100) NOT NULL
);


--
-- Name: med_kits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.med_kits (
    id uuid NOT NULL
);


--
-- Name: parsed_drugs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.parsed_drugs (
    otc boolean NOT NULL,
    quantity integer,
    form_type_id uuid,
    id uuid NOT NULL,
    quantity_unit_id uuid,
    country character varying(100),
    active_substance character varying(300),
    category character varying(300),
    manufacturer character varying(300) NOT NULL,
    name character varying(300) NOT NULL,
    description text
);


--
-- Name: quantity_units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quantity_units (
    id uuid NOT NULL,
    name character varying(30) NOT NULL
);


--
-- Name: user_drugs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_drugs (
    quantity double precision NOT NULL,
    id uuid NOT NULL,
    med_kit_id uuid NOT NULL,
    quantity_unit character varying(50) NOT NULL,
    country character varying(100),
    form_type character varying(100),
    category character varying(200),
    manufacturer character varying(300),
    name character varying(300) NOT NULL,
    description text
);


--
-- Name: user_med_kits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_med_kits (
    med_kit_id uuid NOT NULL,
    user_id uuid NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid NOT NULL,
    hashed_key character varying(255) NOT NULL
);


--
-- Name: usings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.usings (
    planned_amount double precision NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_modified timestamp(6) with time zone NOT NULL,
    drug_id uuid NOT NULL,
    user_id uuid NOT NULL
);


--
-- Name: form_types form_types_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.form_types
    ADD CONSTRAINT form_types_name_key UNIQUE (name);


--
-- Name: form_types form_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.form_types
    ADD CONSTRAINT form_types_pkey PRIMARY KEY (id);


--
-- Name: users ix_users_hashed_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT ix_users_hashed_key UNIQUE (hashed_key);


--
-- Name: med_kits med_kits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.med_kits
    ADD CONSTRAINT med_kits_pkey PRIMARY KEY (id);


--
-- Name: parsed_drugs parsed_drugs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parsed_drugs
    ADD CONSTRAINT parsed_drugs_pkey PRIMARY KEY (id);


--
-- Name: quantity_units quantity_units_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quantity_units
    ADD CONSTRAINT quantity_units_name_key UNIQUE (name);


--
-- Name: quantity_units quantity_units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quantity_units
    ADD CONSTRAINT quantity_units_pkey PRIMARY KEY (id);


--
-- Name: user_drugs user_drugs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_drugs
    ADD CONSTRAINT user_drugs_pkey PRIMARY KEY (id);


--
-- Name: user_med_kits user_med_kits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_med_kits
    ADD CONSTRAINT user_med_kits_pkey PRIMARY KEY (med_kit_id, user_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: usings usings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usings
    ADD CONSTRAINT usings_pkey PRIMARY KEY (drug_id, user_id);


--
-- Name: idx_drugs_form_type_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_drugs_form_type_id ON public.parsed_drugs USING btree (form_type_id);


--
-- Name: idx_drugs_quantity_unit_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_drugs_quantity_unit_id ON public.parsed_drugs USING btree (quantity_unit_id);


--
-- Name: ix_drugs_active_substance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_drugs_active_substance ON public.parsed_drugs USING btree (active_substance);


--
-- Name: ix_drugs_manufacturer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_drugs_manufacturer ON public.parsed_drugs USING btree (manufacturer);


--
-- Name: ix_drugs_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_drugs_name ON public.parsed_drugs USING btree (name);


--
-- Name: ix_user_drugs_med_kit_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_user_drugs_med_kit_id ON public.user_drugs USING btree (med_kit_id);


--
-- Name: ix_user_drugs_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_user_drugs_name ON public.user_drugs USING btree (name);


--
-- Name: ix_usings_drug_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_usings_drug_id ON public.usings USING btree (drug_id);


--
-- Name: ix_usings_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_usings_user_id ON public.usings USING btree (user_id);


--
-- Name: user_drugs fk4dagfmqxds9eaosyjl1ayb8bi; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_drugs
    ADD CONSTRAINT fk4dagfmqxds9eaosyjl1ayb8bi FOREIGN KEY (med_kit_id) REFERENCES public.med_kits(id);


--
-- Name: usings fkawo725lndcilw9mdqu1gkh8pb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usings
    ADD CONSTRAINT fkawo725lndcilw9mdqu1gkh8pb FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_med_kits fkdeftqkofx862kbk1ut4m8ct5i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_med_kits
    ADD CONSTRAINT fkdeftqkofx862kbk1ut4m8ct5i FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: parsed_drugs fkdye7gtwbesu24yn8jkn5vd973; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parsed_drugs
    ADD CONSTRAINT fkdye7gtwbesu24yn8jkn5vd973 FOREIGN KEY (quantity_unit_id) REFERENCES public.quantity_units(id);


--
-- Name: user_med_kits fkfq2u4b03anlsbkut3kxbkppt7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_med_kits
    ADD CONSTRAINT fkfq2u4b03anlsbkut3kxbkppt7 FOREIGN KEY (med_kit_id) REFERENCES public.med_kits(id);


--
-- Name: parsed_drugs fkga8mg1eq3id7si55y7d9y8oxx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parsed_drugs
    ADD CONSTRAINT fkga8mg1eq3id7si55y7d9y8oxx FOREIGN KEY (form_type_id) REFERENCES public.form_types(id);


--
-- Name: usings fkt8jdaufr88rfacl2pre95misk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usings
    ADD CONSTRAINT fkt8jdaufr88rfacl2pre95misk FOREIGN KEY (drug_id) REFERENCES public.user_drugs(id);


--
--
