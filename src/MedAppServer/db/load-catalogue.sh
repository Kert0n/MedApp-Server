#!/bin/sh
# Loads the optional data-only catalogue after 01-schema.sql.
# The catalogue directory is mounted because the closed-data SQL file may be absent.

set -eu

CATALOGUE=/catalogue/cleaned-init.sql

if [ ! -f "$CATALOGUE" ]; then
    echo "load-catalogue: $CATALOGUE не найден — справочник не загружается."
    echo "load-catalogue: приложение поднимется, но поиск по каталогу вернёт пустой список."
    exit 0
fi

# Reject scraper schema dumps; initialization accepts data-only catalogue SQL.
if grep -q '^CREATE TABLE public\.drugs' "$CATALOGUE"; then
    echo "load-catalogue: $CATALOGUE — необработанная выгрузка скраппера." >&2
    echo "load-catalogue: примените db/rewrite-catalogue-dump.py и пересоздайте том." >&2
    exit 1
fi

echo "load-catalogue: загружаю справочник из $CATALOGUE"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -q -f "$CATALOGUE"

echo "load-catalogue: готово"
