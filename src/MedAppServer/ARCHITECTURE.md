# Архитектура MedApp Server

## Границы агрегатов

`Drug`, `Using` и `MedKit` изменяются как отдельные агрегаты.

- `Drug` владеет остатком и описательными полями препарата.
- `Using` владеет плановым количеством одного пользователя для одного препарата.
- `MedKit` задаёт membership и доступ к находящимся в ней препаратам.
- Чтение связанного агрегата разрешено; команда над несколькими агрегатами выполняется
  транзакционным оркестратором.

Обратных коллекций `Drug.usings`, `User.usings` и `MedKit.drugs` нет. Прямые ссылки
`Using.user`, `Using.drug` и `Drug.medKit` ленивые. JPA-сущности не являются HTTP-контрактом:
read-сервисы и команды возвращают неизменяемые проекции.

`Drug.totalPlannedAmount` — read-only `@Formula`, а `availableQuantity` вычисляется как
`quantity - totalPlannedAmount`. Значение формулы относится к моменту загрузки сущности;
команда, изменившая планы, обновляет локальную сумму, если использует её дальше в той же
транзакции.

## Прикладные сервисы

### Чтение

| Сервис | Публичные операции |
|---|---|
| `DrugService` | `getAccessible` |
| `UsingService` | `listForUser`, `getForUser` |
| `MedKitQueryService` | `listForUser`, `getContent`, `getUserSnapshot` |
| `VidalDrugService` | поиск и карточка справочника |

Read-сервисы не предоставляют `save`, `delete`, методы блокировки или варианты одного чтения
с разными графами JPA. Граф загрузки и блокировка являются деталями repository-команд.

### Команды

| Сервис | Ответственность |
|---|---|
| `DrugCommandService` | создание, исправление, списание, перенос и удаление Drug |
| `TreatmentPlanService` | создание, изменение, удаление Using и применение intake |
| `MedKitLifecycleService` | создание, приглашение, вступление, выход и удаление MedKit |
| `IntakeService` | идемпотентность intake и кеширование закоммиченного результата |

Оркестраторы вправе обращаться к нескольким репозиториям. Контроллеры обращаются только к
сервисам; репозитории и JPA-сущности за HTTP-границу не выходят.

## Транзакции и блокировки

Команда над препаратом сначала блокирует доступную строку `user_drugs` через
`PESSIMISTIC_WRITE`, затем читает или меняет планы. Этот порядок используют consumption,
patch TreatmentPlan, intake, move и delete.

При удалении аптечки её препараты блокируются одним запросом в порядке UUID. Планы
пользователя при выходе и планы без доступа при переносе удаляются bulk-операторами.
Альтернативных repository-методов с другим порядком блокировок нет.

`IntakeService` не открывает собственную транзакцию. Он сериализует одинаковые intake ID,
вызывает транзакционный `TreatmentPlanService.applyIntake` и записывает результат в кеш
только после успешного возврата метода. Повтор с тем же payload возвращает сохранённый
результат; другой payload с тем же ID даёт `409 Conflict`.

## Количества и согласование планов

Остатки и планы хранятся как `NUMERIC(19,6)` и нормализуются в сеттерах сущностей.
`PlanReconciler` не обращается к базе и применяет единый алгоритм:

1. считает коэффициент с дополнительной точностью;
2. умножает каждый план на коэффициент;
3. округляет каждый результат вниз до scale 6.

Поэтому сумма согласованных планов не превышает остаток. Consumption, обнуляющий остаток,
сразу удаляет Drug; дочерние планы удаляет PostgreSQL cascade без предварительной загрузки.

## Схема и каскады

PostgreSQL отвечает за каскадное удаление:

- `usings.drug_id -> user_drugs.id ON DELETE CASCADE`;
- `user_drugs.med_kit_id -> med_kits.id ON DELETE CASCADE`;
- `user_med_kits.med_kit_id -> med_kits.id ON DELETE CASCADE`.

[db/schema.sql](db/schema.sql) создаёт новую базу. Существующая база обновляется
идемпотентным скриптом [db/migrate-drug-using-cascades.sql](db/migrate-drug-using-cascades.sql):

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f db/migrate-drug-using-cascades.sql
```

После обновления приложение должно пройти `ddl-auto=validate`.

## Слои

| Пакет | Назначение |
|---|---|
| `controller` | HTTP-маршруты, авторизация и коды ответа |
| `api` | DTO и чистые мапперы |
| `services.orchestrators` | транзакционные команды и составные чтения |
| `services.models` | read-сервисы, read-модели и командные значения |
| `services.security` | JWT, ключи и rate limiting |
| `db.repository` | use-case запросы и проекции хранилища |
| `db.model` | JPA-сущности и локальные инварианты |
| `config` | composition root |

`ArchitectureTest` закрепляет направление зависимостей, отсутствие repository-доступа из
контроллеров и запрет координации чужого агрегата в model/read-сервисе.

## Ресурсный API

Публичное имя плана — TreatmentPlan; внутренние сущность `Using` и таблица `usings`
сохраняются.

- `/v1/drugs` — препараты и consumptions;
- `/v1/treatment-plans` — собственные планы пользователя;
- `/v1/intakes/{intakeId}` — идемпотентная регистрация приёма;
- `/v1/med-kits` и `/v1/med-kit-memberships` — аптечки и участие;
- `/v1/users/me` — пользовательский snapshot;
- `/v1/drug-templates` — справочник.

PATCH использует `null` как «поле не передано». Удаление выражается только HTTP DELETE,
нулевое количество не является командой.

## Проверки запросов

```bash
./gradlew test --no-daemon
./gradlew queryPlanTest --no-daemon
```

`RecordingDataSource` сохраняет SQL fingerprints, тип оператора, batch-выполнения и
упорядоченные параметры. Масштабные сценарии выполняются после warm-up с очищенным
persistence context и сравнивают точную форму SQL на нескольких размерах результата.
`queryPlanTest` поднимает PostgreSQL fixture с 10 000 препаратов, примерно 30 000 планов и
18 000 записей каталога. Каждый уникальный SQL проверяется через natural и forced-index
`EXPLAIN (FORMAT JSON)` без `ANALYZE`.

После прогона формируются фактические отчёты:

- `build/reports/query-plans/database-query-report.md`;
- `build/reports/query-plans/database-query-report.json`.

Асимптотика вычисляется из измеренных точек. Постоянная форма помечается `Θ(1)`, чистые
операции — `0 SQL`; линейный рост допускается только для UPDATE действительно изменённых
Using при reconciliation. Необъяснённое изменение формы проваливает набор.

CI запускает `test` и `queryPlanTest` независимыми jobs и публикует оба отчёта.
