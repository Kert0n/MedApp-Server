#!/usr/bin/env python3
"""Convert a scraper pg_dump into data-only COPY statements for the application schema.

Usage: python3 db/rewrite-catalogue-dump.py init-scripts/cleaned-init.sql

The output contains form_types, quantity_units and parsed_drugs in foreign-key order.
Catalogue UUIDs are derived deterministically from scraper drug_id values. Columns outside
the public catalogue model are omitted, missing manufacturers receive a stable fallback,
and PostgreSQL statistics are refreshed after loading. The source is preserved as `.orig`.
Running the command on an already converted file is a no-op.
"""

import re
import sys
import uuid
from pathlib import Path

# Фиксированное пространство имён для uuid5. Произвольная константа: смысл не в значении, а
# в том, что оно не меняется между прогонами.
NAMESPACE = uuid.UUID("6f2a1c48-0b3d-5e7a-9c14-2d8b5f3e7a10")

MANUFACTURER_FALLBACK = "Не указан"

# Колонки parsed_drugs в том порядке, в каком их получит COPY. search_tsv отсутствует
# намеренно: колонка генерируемая, база считает её сама.
TARGET_COLUMNS = [
    "id", "name", "name_lat", "form_type_id", "quantity", "quantity_unit_id",
    "active_substance", "category", "manufacturer", "country", "description", "otc",
]

HEADER = """--
-- Справочник препаратов: данные для parsed_drugs, form_types и quantity_units.
--
-- Получен из выгрузки скраппера обработкой db/rewrite-catalogue-dump.py — см. её описание.
-- Схему создаёт db/schema.sql, который применяется раньше, поэтому здесь только данные.
--
-- Файл в git не попадает: это закрытые данные.
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;
SET row_security = off;
"""

# Статистики после COPY нет, и планировщик выбирает последовательное сканирование вместо
# индексов поиска — на реальных данных это 47 мс против 2 мс на первых запросах, пока не
# придёт autovacuum.
FOOTER = """
ANALYZE public.form_types;
ANALYZE public.quantity_units;
ANALYZE public.parsed_drugs;
"""


def read_copy_blocks(lines):
    """Находит все COPY ... FROM stdin в дампе.

    Формат COPY TEXT экранирует табуляции и переводы строк внутри значений, поэтому строка
    данных всегда физическая строка файла, а разбиение по '\\t' безопасно.
    """
    blocks = {}
    i = 0
    while i < len(lines):
        match = re.match(r"^COPY public\.(\w+) \(([^)]*)\) FROM stdin;$", lines[i])
        if match:
            start = i + 1
            end = start
            while lines[end] != "\\.":
                end += 1
            blocks[match.group(1)] = (match.group(2).split(", "), lines[start:end])
            i = end
        i += 1
    return blocks


def render_copy(table, columns, rows):
    head = f"COPY public.{table} ({', '.join(columns)}) FROM stdin;"
    return "\n".join([head, *rows, "\\."]) + "\n"


def convert_drug_rows(columns, rows):
    at = {name: index for index, name in enumerate(columns)}
    seen = set()
    converted = []
    for row in rows:
        field = row.split("\t")

        drug_id = field[at["drug_id"]]
        if drug_id in seen:
            raise SystemExit(f"drug_id повторяется: {drug_id!r} — uuid5 дал бы коллизию ключа")
        seen.add(drug_id)

        manufacturer = field[at["manufacturer"]]
        otc = field[at["otc"]]
        converted.append("\t".join([
            str(uuid.uuid5(NAMESPACE, drug_id)),
            field[at["name"]],
            field[at["name_lat"]],
            field[at["form_type_id"]],
            field[at["quantity"]],
            field[at["quantity_unit_id"]],
            field[at["active_substance"]],
            field[at["category"]],
            MANUFACTURER_FALLBACK if manufacturer == "\\N" else manufacturer,
            field[at["country"]],
            field[at["description"]],
            "f" if otc == "\\N" else otc,
        ]))
    return converted


def main():
    if len(sys.argv) != 2:
        raise SystemExit(f"использование: {sys.argv[0]} <дамп.sql>")

    path = Path(sys.argv[1])
    if not path.is_file():
        raise SystemExit(f"{path} не найден")

    lines = path.read_text(encoding="utf-8").split("\n")
    blocks = read_copy_blocks(lines)

    if "drugs" not in blocks:
        print(f"{path}: таблицы drugs нет — файл уже обработан, ничего не делаю")
        return

    for table in ("form_types", "quantity_units"):
        if table not in blocks:
            raise SystemExit(f"в дампе нет данных {table} — на них ссылается parsed_drugs")

    drug_columns, drug_rows = blocks["drugs"]
    missing = [c for c in TARGET_COLUMNS if c != "id" and c not in drug_columns]
    if missing:
        raise SystemExit(f"в выгрузке нет колонок {missing} — структура дампа изменилась")

    parsed = convert_drug_rows(drug_columns, drug_rows)

    out = [HEADER]
    for table in ("form_types", "quantity_units"):
        columns, rows = blocks[table]
        out.append(render_copy(table, columns, rows))
    out.append(render_copy("parsed_drugs", TARGET_COLUMNS, parsed))
    out.append(FOOTER)

    backup = path.with_suffix(path.suffix + ".orig")
    if not backup.exists():
        path.replace(backup)
    result = "\n".join(out)
    path.write_text(result, encoding="utf-8")

    print(f"{path}: {len(parsed)} препаратов, {len(blocks['form_types'][1])} форм выпуска, "
          f"{len(blocks['quantity_units'][1])} единиц измерения")
    print(f"исходная выгрузка сохранена в {backup.name}")


if __name__ == "__main__":
    main()
