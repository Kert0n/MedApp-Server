# Архитектура MedApp Server

## Контекст

MedApp Server — модульный монолит. Он синхронно обрабатывает REST-запросы, хранит текущее состояние в PostgreSQL и использует локальный временный cache для приглашений и регистрационного throttle.

```text
Client
  │ HTTPS
  ▼
Caddy
  │ trusted X-Forwarded-*
  ▼
Spring MVC / Security
  ▼
Application services
  ▼
JPA repositories
  ▼
PostgreSQL
```

## Слои

- `controller` — HTTP contract, validation и mapping;
- `services.models` — операции над отдельными агрегатами;
- `services.orchestrators` — транзакционные сценарии над несколькими агрегатами;
- `services.security` — JWT, credentials, client address и throttling;
- `db.model` и `db.repository` — persistence model и access-scoped queries;
- `config` — типизированные настройки.

Web DTO не возвращают JPA entities. Authentication principal отделён от `User` entity, поэтому persistence model не реализует Spring Security interfaces.

## Авторизация

Авторизация основана только на membership. Запросы ищут drug/medkit одновременно по resource ID и authenticated user ID. Отсутствующий и недоступный ресурс возвращают одинаковый `404`.

Owner и роли отсутствуют. Любой участник может менять общую аптечку и удалить её для всех. `/leave` удаляет только текущего участника.

## Конкурентность

Операции изменения stock и treatment plans сериализуются pessimistic lock на строке drug. Это предотвращает отрицательный остаток без `@Version` и без дополнительной metadata.

Количества представлены как `BigDecimal` и `NUMERIC(19,6)`. При пропорциональном уменьшении округление выполняется с `HALF_UP`, а residual добавляется к детерминированно выбранному плану, чтобы сумма оставалась точно равна stock.

## Временные cache

- share cache: `SHA-256(token) → medKitId`, TTL, token многоразовый до TTL;
- registration cache: `HMAC(processKey, IP) → AtomicInteger`, фиксированное окно;
- оба cache локальны процессу и очищаются при рестарте;
- Aedile остаётся Kotlin API над Caffeine.

## Database evolution

Flyway является единственным production-механизмом изменения схемы. Hibernate работает с `ddl-auto=validate`. Existing schema получает baseline version 1, затем применяется decimal migration.

## Operational boundary

Caddy — единственный доверенный proxy. Tomcat принимает forwarded headers только из выделенной edge Docker-сети. Application и PostgreSQL не имеют host port в production Compose.

Подробнее: [DEPLOYMENT.md](DEPLOYMENT.md) и [SECURITY.md](SECURITY.md).
