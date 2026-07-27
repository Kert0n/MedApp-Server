-- Наполняет parsed_drugs — каталог, по которому ищет приложение.
--
-- drugs приходит из дампа со своими колонками (drug_id, name_lat, form, dosage, url) и
-- служит промежуточной таблицей: после переноса она удаляется, см. конец файла.
-- parsed_drugs принадлежит приложению (сущность VidalDrug), поэтому нужен перенос, а не
-- переименование.
--
-- Отбрасываются drug_id, form (текстовый дубль нормализованного form_type_id), dosage и
-- url — в сущности их нет. name_lat переносится: по нему идёт поиск, и без него запрос по
-- латинскому названию не находил ничего вообще.
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

    INSERT INTO parsed_drugs (id, name, name_lat, form_type_id, quantity, quantity_unit_id,
                              active_substance, category, manufacturer, country,
                              description, otc)
    SELECT gen_random_uuid(),
           d.name,
           d.name_lat,
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

    -- drugs — промежуточная таблица дампа, в рабочей базе ей делать нечего.
    --
    -- Во-первых, справочник иначе лежит дважды: 18087 строк в drugs и столько же в
    -- parsed_drugs. Во-вторых, имя путается с user_drugs — таблицей препаратов самого
    -- приложения, и по названию не угадать, где чужой справочник, а где данные пользователя.
    --
    -- Повторный перенос делается пересозданием тома, тем же порядком, что и смена схемы:
    -- дамп остаётся на диске, init прогоняется заново.
    DROP TABLE public.drugs;
    RAISE NOTICE 'fill-parsed-drugs: промежуточная таблица drugs удалена';
END $$;
