#!/bin/sh
# Загружает дамп справочника, если он есть.
#
# Дамп (init-scripts/cleaned-init.sql, ~57 МБ) — закрытые данные, в git его нет. Поэтому
# монтировать сам файл в docker-entrypoint-initdb.d нельзя: при отсутствии пути Docker
# создаёт на его месте каталог, и init базы падает. Вместо этого монтируется каталог
# init-scripts целиком (его отсутствие безвредно), а этот скрипт решает, есть что грузить
# или нет.
#
# Порядок в docker-entrypoint-initdb.d: сначала этот скрипт (form_types, quantity_units,
# drugs с данными), затем 02-schema.sql (остальные таблицы приложения), затем
# 03-fill-parsed-drugs.sql (перенос drugs -> parsed_drugs).

set -eu

CATALOGUE=/catalogue/cleaned-init.sql

if [ ! -f "$CATALOGUE" ]; then
    echo "load-catalogue: $CATALOGUE не найден — справочник не загружается."
    echo "load-catalogue: приложение поднимется, но поиск по каталогу вернёт пустой список."
    exit 0
fi

run_sql() {
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -c "$1"
}

# В дампе четыре строки ALTER ... OWNER TO vidal — это владелец той базы, откуда его
# снимали. Без такой роли psql падает. Роль создаётся временно, только чтобы дамп
# применился, и убирается сразу после с передачей владения рабочему пользователю: иначе
# таблицы справочника остались бы за чужой ролью.
echo "load-catalogue: создаю временную роль vidal (владелец в дампе)"
run_sql "DO \$\$ BEGIN
             IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vidal') THEN
                 CREATE ROLE vidal NOLOGIN;
             END IF;
         END \$\$;"

echo "load-catalogue: загружаю справочник из $CATALOGUE"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -f "$CATALOGUE"

echo "load-catalogue: передаю владение $POSTGRES_USER и удаляю роль vidal"
run_sql "REASSIGN OWNED BY vidal TO \"$POSTGRES_USER\";"
run_sql "DROP ROLE vidal;"

echo "load-catalogue: готово"
