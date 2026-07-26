-- Наполняет parsed_drugs — каталог, по которому ищет приложение.
--
-- drugs и parsed_drugs это разные таблицы: drugs приходит из дампа со своими колонками
-- (drug_id, name_lat, form, dosage, url), parsed_drugs принадлежит приложению
-- (сущность VidalDrug). Поэтому нужен перенос, а не переименование. Колонки, которых в
-- сущности нет, отбрасываются: form_type_id это нормализованная ссылка, form — её
-- текстовый вариант.
--
-- Таблица drugs остаётся в базе: она источник, из которого перенос можно повторить.
--
-- Расхождение по nullability одно: manufacturer в drugs может быть NULL (5 строк из
-- 18087), а в сущности он NOT NULL. Такие записи грузятся с заглушкой, чтобы каталог
-- остался полным. otc в данных заполнен везде, COALESCE на него — страховка от будущих
-- дампов.

DO $$
DECLARE
    loaded bigint;
BEGIN
    IF to_regclass('public.drugs') IS NULL THEN
        RAISE NOTICE 'fill-parsed-drugs: таблицы drugs нет, справочник не загружался — parsed_drugs остаётся пустым';
        RETURN;
    END IF;

    -- Идемпотентность: initdb выполняется один раз на пустом томе, но скрипт может быть
    -- запущен и вручную, а дублировать 18 тысяч записей незачем.
    IF EXISTS (SELECT 1 FROM parsed_drugs) THEN
        RAISE NOTICE 'fill-parsed-drugs: parsed_drugs уже наполнен, пропускаю';
        RETURN;
    END IF;

    INSERT INTO parsed_drugs (id, name, form_type_id, quantity, quantity_unit_id,
                              active_substance, category, manufacturer, country,
                              description, otc)
    SELECT gen_random_uuid(),
           d.name,
           d.form_type_id,
           d.quantity,
           d.quantity_unit_id,
           d.active_substance,
           d.category,
           COALESCE(d.manufacturer, 'Не указан'),
           d.country,
           d.description,
           COALESCE(d.otc, false)
    FROM public.drugs d;

    GET DIAGNOSTICS loaded = ROW_COUNT;
    RAISE NOTICE 'fill-parsed-drugs: перенесено % записей из drugs', loaded;
END $$;
