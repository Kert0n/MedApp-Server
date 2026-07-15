# Architecture

## Runtime

```text
Client -> Caddy -> MedApp Server -> PostgreSQL
```

Caddy завершает TLS и передаёт HTTP-запросы приложению. MedApp Server выполняет authentication, validation и business logic. PostgreSQL хранит текущее состояние.

## Application layers

- `controller` — HTTP API и DTO;
- `services.models` — операции над отдельными моделями;
- `services.orchestrators` — операции, затрагивающие несколько моделей;
- `services.security` — authentication и registration throttling;
- `db.model` / `db.repository` — persistence;
- `db/migration` — Flyway migrations.

Controllers получают authenticated user и передают его identifier в services. Services проверяют доступ через membership общей аптечки и выполняют изменения внутри transactions.

Количества представлены как `BigDecimal` и PostgreSQL `NUMERIC(19,6)`. Concurrent stock changes не добавляют version metadata в пользовательские данные.

Временные registration/share данные не сохраняются и исчезают при restart.

Production schema изменяется только Flyway; Hibernate использует `ddl-auto=validate`.
