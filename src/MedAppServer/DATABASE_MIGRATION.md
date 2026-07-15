# Database migration

## Новая БД

Flyway выполняет:

1. `V1__baseline.sql` — schema, indexes и `pg_trgm`;
2. `V2__exact_decimal_quantities.sql` — `NUMERIC(19,6)` и non-negative constraints.

## Существующая БД

1. Остановить application или перевести её в read-only maintenance window.
2. Создать проверенный backup.
3. Убедиться, что текущая schema соответствует JPA model до рефакторинга.
4. Запустить Flyway с `baseline-on-migrate=true` и `baseline-version=1`.
5. Flyway пометит существующую schema baseline version 1 и применит V2.
6. Запустить application с `ddl-auto=validate`.

V2 останавливается, если найдены negative, `NaN`, infinity или значения, не помещающиеся в `NUMERIC(19,6)`. Она не исправляет такие данные молча.

Проверки до запуска:

```sql
SELECT id, quantity
FROM user_drugs
WHERE quantity < 0 OR quantity::text IN ('NaN', 'Infinity', '-Infinity');

SELECT user_id, drug_id, planned_amount
FROM usings
WHERE planned_amount < 0 OR planned_amount::text IN ('NaN', 'Infinity', '-Infinity');
```

Rollback V2 требует восстановления backup: преобразование floating-point в exact decimal необратимо без потери исходного binary representation.
