# Архитектура MedApp Server

## Поток вызова

Команда проходит по цепочке `Controller → Orchestrator → Aggregate Service → Repository`.
Оркестратор открывает транзакцию и задаёт порядок действий. Aggregate service работает с
одним агрегатом и использует `Propagation.MANDATORY`, поэтому не может незаметно открыть
отдельную транзакцию. Repository отвечает за SQL, блокировки и bulk-операции.

Чтение проходит по цепочке `Controller → Query Service → Projection Repository` внутри read-only
транзакции. Наружу возвращаются immutable-проекции, а не JPA-сущности.

## Агрегаты

`Drug` владеет остатком и `TreatmentPlanBook`. В доменной модели находятся проверка количеств,
consumption, intake, создание и изменение плана, reconciliation и решение о планах при переносе.
Таблица плана сохраняет имя `usings`, публичное имя — `TreatmentPlan`.

`MedKit` владеет membership. Участники равноправны; выход последнего участника удаляет аптечку. Drug
ссылается на MedKit, но не входит в его JPA-граф. Связь участников представлена сущностью
`MedKitMembership`.

JPA-сущности не имеют обратных изменяемых коллекций. Командный агрегат загружается явным
repository-запросом, а каскадное удаление детей выполняют внешние ключи PostgreSQL.

## Транзакции и блокировки

- Drug-команды блокируют Drug до загрузки TreatmentPlan.
- Создание Drug блокирует MedKit.
- Move блокирует MedKit по UUID, затем Drug и TreatmentPlan.
- Leave/delete MedKit блокирует MedKit по UUID, затем Drug по UUID и TreatmentPlan.
- Intake записывается в idempotency-cache только после успешного commit.

Операции над коллекциями используют bulk SQL. Единственный разрешённый линейный рост DML — один
`UPDATE usings` на каждый реально изменённый план при reconciliation.

## Схема и контроль запросов

`db/schema.sql` — полное описание новой базы; ALTER/migration-скриптов нет. Production и query-plan профиль
запускают Hibernate с `ddl-auto=validate`.

N+1-гейты используют Hibernate Statistics на нескольких размерах fixture. Отдельный `RecordingDataSource`
сохраняет фактические SQL fingerprints и параметры, а PostgreSQL `EXPLAIN (FORMAT JSON)` проверяет планы и
индексы. Отчёты формируются в `build/reports/query-plans` и публикуются CI-артефактом.

## Границы зависимостей

- controller не импортирует repository или JPA-модель;
- orchestrator не импортирует repository или JPA-модель;
- domain не зависит от Spring, JPA и HTTP;
- application service не зависит от API/HTTP;
- ошибки domain/application не содержат HTTP-статусов; преобразование выполняет `ApiExceptionHandler`.

Эти правила и `Propagation.MANDATORY` закреплены в `ArchitectureTest`.
