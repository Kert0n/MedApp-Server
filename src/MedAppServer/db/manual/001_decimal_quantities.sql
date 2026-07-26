-- Количества препаратов и планов: double precision -> numeric(19,6).
--
-- Зачем: на double дробные дозы не сходились. После накопленных вычитаний остаток никогда
-- не равнялся ровно нулю, поэтому кончившийся препарат не удалялся, а планы оставались с
-- остатком порядка 1e-16, и инвариант «сумма планов не больше остатка» тихо нарушался.
--
-- Применять с остановленным сервером: в проде стоит ddl-auto=validate, и до правки он
-- откажется стартовать на новой версии кода.
--
--   docker compose -f compose.yaml stop med-app-server
--   docker compose -f compose.yaml exec -T postgres \
--     psql -U medapp -d medapp-server-db -v ON_ERROR_STOP=1 < db/manual/001_decimal_quantities.sql
--   docker compose -f compose.yaml start med-app-server
--
-- Округление: значения, накопившие мусор в младших разрядах, приводятся к шести знакам.
-- Идемпотентно — повторный запуск ничего не меняет.

ALTER TABLE user_drugs
    ALTER COLUMN quantity TYPE numeric(19, 6) USING ROUND(quantity::numeric, 6);

ALTER TABLE usings
    ALTER COLUMN planned_amount TYPE numeric(19, 6) USING ROUND(planned_amount::numeric, 6);
