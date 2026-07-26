-- Снятие created_at и last_modified с таблицы usings.
--
-- Поля были write-only: заполнялись, вытекали в UsingDTO и больше нигде не использовались —
-- ни в одном запросе, сортировке или фильтре. При этом «дата начала курса + название
-- препарата» — самая говорящая пара в этой базе, а расписание приёма по замыслу живёт на
-- клиенте, серверу эти даты не нужны.
--
-- Применять с остановленным сервером: в проде ddl-auto=validate, и до правки он откажется
-- стартовать на новой версии кода. Обратной операции нет — данные удаляются безвозвратно,
-- это и есть цель.
--
--   docker compose -f compose.yaml stop med-app-server
--   docker compose -f compose.yaml exec -T postgres \
--     psql -U medapp -d medapp-server-db -v ON_ERROR_STOP=1 < db/manual/002_drop_using_timestamps.sql
--   docker compose -f compose.yaml start med-app-server

ALTER TABLE usings DROP COLUMN IF EXISTS created_at;
ALTER TABLE usings DROP COLUMN IF EXISTS last_modified;
