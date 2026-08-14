#!/bin/sh
# Загружает справочник препаратов, если дамп есть.
#
# Дамп (init-scripts/cleaned-init.sql, ~57 МБ) — закрытые данные, в git его нет. Поэтому
# монтировать сам файл в docker-entrypoint-initdb.d нельзя: при отсутствии пути Docker
# создаёт на его месте каталог, и init базы падает. Вместо этого монтируется каталог
# init-scripts целиком (его отсутствие безвредно), а этот скрипт решает, есть что грузить
# или нет.
#
# Порядок в docker-entrypoint-initdb.d: сначала 01-schema.sql (все таблицы приложения,
# включая form_types, quantity_units и parsed_drugs), затем этот скрипт — он приносит
# только данные.
#
# Раньше порядок был обратным, а дамп нёс собственную таблицу drugs, которую третий шаг
# переливал в parsed_drugs и удалял. Ту же работу это делало дважды и требовало временной
# роли vidal — владельца в выгрузке. Дамп обрабатывается db/rewrite-catalogue-dump.py, и
# ни того, ни другого в нём больше нет.

set -eu

CATALOGUE=/catalogue/cleaned-init.sql

if [ ! -f "$CATALOGUE" ]; then
    echo "load-catalogue: $CATALOGUE не найден — справочник не загружается."
    echo "load-catalogue: приложение поднимется, но поиск по каталогу вернёт пустой список."
    exit 0
fi

# Признак необработанной выгрузки. Такая создаёт таблицу drugs и требует роль vidal —
# сейчас она не применится, и сказать об этом лучше сразу, чем дать psql упасть на середине
# с сообщением про несуществующую роль.
if grep -q '^CREATE TABLE public\.drugs' "$CATALOGUE"; then
    echo "load-catalogue: $CATALOGUE — необработанная выгрузка скраппера." >&2
    echo "load-catalogue: примените db/rewrite-catalogue-dump.py и пересоздайте том." >&2
    exit 1
fi

echo "load-catalogue: загружаю справочник из $CATALOGUE"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -f "$CATALOGUE"

echo "load-catalogue: готово"
